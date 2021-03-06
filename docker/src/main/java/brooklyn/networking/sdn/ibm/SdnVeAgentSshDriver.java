/*
 * Copyright 2014-2015 by Cloudsoft Corporation Limited
 */
package brooklyn.networking.sdn.ibm;

import static brooklyn.util.ssh.BashCommands.sudo;

import java.net.InetAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractSoftwareProcessSshDriver;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.basic.EntityPredicates;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.basic.lifecycle.ScriptHelper;
import brooklyn.entity.container.docker.DockerContainer;
import brooklyn.entity.container.docker.DockerInfrastructure;
import brooklyn.entity.software.SshEffectorTasks;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.networking.VirtualNetwork;
import brooklyn.networking.sdn.SdnAgent;
import brooklyn.networking.sdn.SdnProvider;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.Urls;
import brooklyn.util.ssh.BashCommands;
import brooklyn.util.task.DynamicTasks;
import brooklyn.util.task.Tasks;
import brooklyn.util.text.Identifiers;
import brooklyn.util.text.StringPredicates;
import brooklyn.util.text.Strings;

import com.google.common.base.CharMatcher;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SdnVeAgentSshDriver extends AbstractSoftwareProcessSshDriver implements SdnVeAgentDriver {

    private static final Logger LOG = LoggerFactory.getLogger(SdnVeAgent.class);

    public SdnVeAgentSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    @Override
    public void preInstall() {
        resolver = Entities.newDownloader(this);
    }

    @Override
    public void install() {
        List<String> commands = Lists.newLinkedList();
        commands.addAll(BashCommands.commandsToDownloadUrlsAs(resolver.getTargets(), "dove-agent.rpm"));
        commands.add(BashCommands.INSTALL_CURL);
        commands.add(BashCommands.installPackage(MutableMap.builder()
                .put("yum", "iotop libvirt libvirt-python iproute")
                .put("apt", "iotop libvirt-bin libvirt-dev python-libvirt iproute2 rpm")
                .build(), null));
        commands.add(sudo("rpm --nodeps --install dove-agent.rpm"));
        commands.add("wget https://repos.fedorapeople.org/repos/openstack/openstack-icehouse/epel-6/iproute-2.6.32-130.el6ost.netns.2.x86_64.rpm");
        commands.add(sudo("rpm -Uvh iproute-2.6.32-130.el6ost.netns.2.x86_64.rpm"));

        newScript(INSTALLING)
                .body.append(commands)
                .execute();
    }

    @Override
    public void customize() {
        String netstat = getEntity().getAttribute(SdnVeAgent.DOCKER_HOST).execCommand("netstat -rn");
        Iterable<String> routes = Iterables.filter(Splitter.on(CharMatcher.anyOf("\r\n")).split(netstat), StringPredicates.containsLiteral("eth0"));
        String subnetAddress = getEntity().getAttribute(SoftwareProcess.SUBNET_ADDRESS);
        String address = Strings.getFirstWord(Iterables.find(routes, Predicates.and(StringPredicates.containsLiteral("255.255.255."),
                StringPredicates.containsLiteral(subnetAddress.substring(0, subnetAddress.lastIndexOf('.'))))));
        String gateway = Strings.getFirstWordAfter(Iterables.find(routes, StringPredicates.containsLiteral("10.0.0.0")), "10.0.0.0");
        LOG.debug("Found gateway {} and address {}", gateway, address);

        List<String> commands = Lists.newLinkedList();

        // Add the DOVE bridge device and configure it appropriately
        commands.add(sudo("brctl addbr br_mgmt_1"));
        commands.add(sudo(String.format("ifconfig br_mgmt_1 %s netmask 255.255.255.224", getEntity().getAttribute(SdnAgent.SDN_AGENT_ADDRESS).getHostAddress())));
        commands.add(sudo("ifconfig eth0 0.0.0.0"));
        commands.add(sudo(String.format("ifconfig br_mgmt_1:1 %s netmask 255.255.255.192", address)));
        commands.add(sudo("brctl addif br_mgmt_1 eth0"));
        commands.add(sudo(String.format("route add -net 10.0.0.0 netmask 255.0.0.0 gw %s", gateway)));

        // Sort out network management and configuration services 
        commands.add(sudo("mv /etc/sysconfig/network-scripts/ifcfg-eth0 /etc/sysconfig/network-scripts/_ifcfg-eth0"));
        commands.add(BashCommands.ok(sudo("service dhcpd stop")));
        commands.add(BashCommands.alternatives(sudo("service libvirtd start"), sudo("service libvirt-bin start"), "true"));
        commands.add("echo 'nameserver 8.8.8.8' | " + sudo("tee /etc/resolv.conf"));

        newScript(CUSTOMIZING)
                .body.append(commands)
                .execute();

        String doveXmlTemplate = processTemplate(entity.config().get(SdnVeNetwork.CONFIGURATION_XML_TEMPLATE));
        String savedDoveXml = Urls.mergePaths(getRunDir(), "dove.xml");
        DynamicTasks.queueIfPossible(SshEffectorTasks.put(savedDoveXml).contents(doveXmlTemplate)).andWaitForSuccess();

        String networkScriptUrl = getEntity().config().get(SdnVeNetwork.NETWORK_SETUP_SCRIPT_URL);
        String networkScript = Urls.mergePaths(getRunDir(), "network.sh");
        DynamicTasks.queueIfPossible(SshEffectorTasks.put(networkScript).contents(resource.getResourceFromUrl(networkScriptUrl))).andWaitForSuccess();

        newScript(CUSTOMIZING)
                .body.append(sudo("mv " + savedDoveXml + " /etc/dove.xml"))
                .body.append("chmod 755 " + networkScript)
                .execute();
    }

    private int createNetwork() {
        String networkId = getEntity().getApplicationId();
        String tenantId = networkId;

        Map<String, String> createNetworkData = ImmutableMap.<String, String>builder()
                .put("networkId", networkId)
                .put("networkName", networkId)
                .put("tenantId", tenantId)
                .build();
        String createNetwork = copyJsonTemplate("create_network.json", createNetworkData);
        callRestApi(createNetwork, "v2.0/networks");

        int doveNetworkId = getNetworkIdForName(networkId);
        return doveNetworkId;
    }

    private void createSubnet(String subnetId, String subnetName, InetAddress gatewayIp, Cidr subnetCidr) {
        String networkId = getEntity().getApplicationId();
        String tenantId = networkId;

        Map<String, String> createSubnetData = ImmutableMap.<String, String>builder()
                .put("subnetId", subnetId)
                .put("subnetName", subnetName)
                .put("networkId", networkId)
                .put("gatewayIp", gatewayIp.getHostAddress())
                .put("networkCidr", subnetCidr.toString())
                .put("tenantId", tenantId)
                .build();
        String createSubnet = copyJsonTemplate("create_subnet.json", createSubnetData);
        callRestApi(createSubnet, "v2.0/subnets");
    }

    private String copyJsonTemplate(String fileName, Map<String, String> substitutions) {
        String contents = processTemplate("classpath://brooklyn/networking/sdn/ibm/" + fileName, substitutions);
        String target = Urls.mergePaths(getRunDir(), Identifiers.makeRandomId(8) + ".json");
        DynamicTasks.queueIfPossible(SshEffectorTasks.put(target).machine(getMachine()).contents(contents)).andWaitForSuccess();
        return target;
    }

    private String callRestApi(String jsonData, String apiPath) {
        String command = String.format("curl -i -u %s:%s -X POST -H \"Content-Type: application/json\" -d @- http://%s/%s < %s",
                getEntity().config().get(SdnVeNetwork.DOVE_CONTROLLER_USERNAME), getEntity().config().get(SdnVeNetwork.DOVE_CONTROLLER_PASSWORD),
                getEntity().config().get(SdnVeNetwork.DOVE_CONTROLLER).getHostAddress(), apiPath, jsonData);
        String output = getEntity().getAttribute(SdnVeAgent.DOCKER_HOST).execCommand(command);
        return output;
    }

    private int getNetworkIdForName(String networkName) {
        String command = String.format("curl -s -u %s:%s http://%s/networks",
                getEntity().config().get(SdnVeNetwork.DOVE_CONTROLLER_USERNAME), getEntity().config().get(SdnVeNetwork.DOVE_CONTROLLER_PASSWORD),
                getEntity().config().get(SdnVeNetwork.DOVE_CONTROLLER).getHostAddress());
        String output = getEntity().getAttribute(SdnVeAgent.DOCKER_HOST).execCommand(command);
        JsonParser parser = new JsonParser();
        JsonElement json = parser.parse(output);
        JsonArray networks = json.getAsJsonObject().get("networks").getAsJsonArray();
        for (JsonElement element : networks) {
            JsonObject network = element.getAsJsonObject();
            int networkId = network.get("network_id").getAsInt();
            int domainId = network.get("domain_id").getAsInt();
            String name = network.get("name").getAsString();
            if (name.endsWith(networkName)) {
                getEntity().setAttribute(SdnVeAgent.DOVE_BRIDGE_ID, networkId);
                getEntity().setAttribute(SdnVeAgent.DOVE_DOMAIN_ID, domainId);
                return networkId;
            }
        }
        throw new IllegalStateException("Cannot find network: " + networkName);
    }

    private void attachPublicAddress(String containerId, InetAddress privateAddress, InetAddress publicAddress) {
        Map<String, String> createSnatRulesData = ImmutableMap.<String, String>builder()
                .put("networkId", Integer.toString(getEntity().getAttribute(SdnVeAgent.DOVE_BRIDGE_ID)))
                .put("domainId", Integer.toString(getEntity().getAttribute(SdnVeAgent.DOVE_DOMAIN_ID)))
                .build();
        String createSnatRules = copyJsonTemplate("create_snat_rules.json", createSnatRulesData);
        callRestApi(createSnatRules, "api/dove/dgw/service/ext-gw");

        Map<String, String> createForwarduingRuleData = ImmutableMap.<String, String>builder()
                .put("networkId", Integer.toString(getEntity().getAttribute(SdnVeAgent.DOVE_BRIDGE_ID)))
                .put("sourceIp", privateAddress.getHostAddress())
                .put("targetIp", publicAddress.getHostAddress())
                .build();
        String createForwarduingRule = copyJsonTemplate("create_forwarding_rule.json", createForwarduingRuleData);
        callRestApi(createForwarduingRule, "api/dove/dgw/service/rule");
    }

    @Override
    public void launch() {
        newScript(MutableMap.of(USE_PID_FILE, false), LAUNCHING)
                .body.append(sudo("start doved"))
                .execute();

        int networkId = createNetwork();

        Map<String, String> createBridgeData = ImmutableMap.<String, String>builder()
                .put("networkId", Integer.toString(networkId))
                .put("agentAddress", getEntity().getAttribute(SdnAgent.SDN_AGENT_ADDRESS).getHostAddress())
                .build();
        String createBridge = copyJsonTemplate("create_bridge.json", createBridgeData);
        String createBridgeOutput = callRestApi(createBridge, "api/dove/vrmgr/vnids/vm_mgr");
        Preconditions.checkState(Strings.containsLiteral(createBridgeOutput, "successfully"), "Failed to export network %s", networkId);

        String bridge = getEntity().getAttribute(SdnVeAgent.DOCKER_HOST).execCommand(sudo("brctl show"));
        String doveBridge = Strings.getFirstWordAfter(bridge, "dovebr_");
        if (Integer.valueOf(doveBridge) != networkId) {
            throw new IllegalStateException("Incorrect network ID found: " + doveBridge);
        }
        LOG.debug("Added bridge: " + doveBridge);

        LOG.info("SDN agent restarting Docker service");
        String restart = getEntity().getAttribute(SdnVeAgent.DOCKER_HOST).execCommand(sudo("service docker restart"));
        Iterable<String> successes = Iterables.filter(Splitter.on(CharMatcher.anyOf("\r\n")).split(restart), StringPredicates.containsLiteral("OK"));
        if (Iterables.size(successes) != 2) {
            throw new IllegalStateException("Failed to restart Docker Engine service: " + restart);
        }
    }

    @Override
    public boolean isRunning() {
        String pingData = getEntity().getAttribute(SdnVeAgent.DOCKER_HOST).execCommand(String.format("ping -c 10 -q %s",
                getEntity().config().get(SdnVeNetwork.DOVE_CONTROLLER).getHostAddress()));
        Double packetLoss = Integer.valueOf(Strings.getFirstWordAfter(pingData, "packets transmitted,")) / 10d;
        // Maybe save as attribute? Check valididty of this reault...

        ScriptHelper helper = newScript(MutableMap.of(USE_PID_FILE, false), CHECK_RUNNING)
                .body.append(sudo("status doved"))
                .noExtraOutput()
                .gatherOutput();
        helper.execute();
        return helper.getResultStdout().contains("running");
    }

    @Override
    public void stop() {
        newScript(MutableMap.of(USE_PID_FILE, false), STOPPING)
                .body.append(sudo("stop doved"))
                .execute();
    }

    @Override
    public void createSubnet(String virtualNetworkId, String subnetId, Cidr subnetCidr) {
        Tasks.setBlockingDetails("Creating " + subnetId);
        try {
            InetAddress gatewayIp = subnetCidr.addressAtOffset(1);

            createSubnet(virtualNetworkId, subnetId, gatewayIp, subnetCidr);
        } finally {
            Tasks.resetBlockingDetails();
        }
    }

    @Override
    public InetAddress attachNetwork(String containerId, String subnetId) {
        Tasks.setBlockingDetails(String.format("Attach %s to %s", containerId, subnetId));
        try {
            InetAddress address = getEntity().getAttribute(SdnAgent.SDN_PROVIDER).getNextContainerAddress(subnetId);

            String networkScript = Urls.mergePaths(getRunDir(), "network.sh");
            Integer bridgeId = getEntity().getAttribute(SdnVeAgent.DOVE_BRIDGE_ID);
            Cidr cidr = getEntity().getAttribute(SdnAgent.SDN_PROVIDER).getSubnetCidr(subnetId);

            /* ./setup_network_v2.sh containerid network_1 12345678 fa:16:50:00:01:e1 50.0.0.2/24 50.0.0.1 8064181 */
            String command = String.format("%s %s %s %s fa:16:%02x:%02x:%02x:%02x %s/%d %s %d %s", networkScript,
                    containerId, // UUID of the Container instance
                    getEntity().getApplicationId(), // Network ID
                    Identifiers.makeRandomId(8), // Port ID unique to container
                    address.getAddress()[0], address.getAddress()[1], address.getAddress()[2], address.getAddress()[3], // Container MAC address
                    address.getHostAddress(), cidr.getLength(), // CIDR IP address assigned to the above interface
                    cidr.addressAtOffset(1).getHostAddress(), // Default gateway assigned to the Container
                    bridgeId, // VNID to be used
                    subnetId); // Interface name
            getEntity().config().get(SdnVeAgent.DOCKER_HOST).execCommand(sudo(command));

            // Lookup the VirtualNetwork entity and check for special requirements (e.g. public address mapping)
            Optional<Entity> found = Iterables.tryFind(getEntity().getAttribute(SdnAgent.SDN_PROVIDER).getAttribute(SdnProvider.SDN_NETWORKS).getMembers(),
                    EntityPredicates.attributeEqualTo(VirtualNetwork.NETWORK_ID, subnetId));
            if (!found.isPresent()) {
                throw new IllegalStateException(String.format("Cannot find virtual network entity for %s", subnetId));
            }
            VirtualNetwork network = (VirtualNetwork) found.get();
            if (Boolean.TRUE.equals(network.config().get(SdnVeAttributes.ENABLE_PUBLIC_ACCESS))) {
                Cidr publicCidr = network.config().get(SdnVeAttributes.PUBLIC_CIDR);
                getEntity().getAttribute(SdnVeAgent.SDN_PROVIDER).recordSubnetCidr(subnetId + ".public", publicCidr, -1);
                InetAddress publicAddress = getEntity().getAttribute(SdnAgent.SDN_PROVIDER).getNextContainerAddress(subnetId + ".public");
                attachPublicAddress(containerId, address, publicAddress);
                Collection<Entity> containers = getEntity().getAttribute(SdnAgent.SDN_PROVIDER)
                        .getAttribute(SdnProvider.DOCKER_INFRASTRUCTURE)
                        .getAttribute(DockerInfrastructure.DOCKER_CONTAINER_FABRIC)
                        .getMembers();
                Optional<DockerContainer> container = Iterables.tryFind(Iterables.filter(containers, DockerContainer.class),
                        EntityPredicates.attributeEqualTo(DockerContainer.CONTAINER_ID, containerId));
                if (!container.isPresent()) {
                    throw new IllegalStateException(String.format("Cannot find container entity for %s", containerId));
                }
                ((EntityLocal) container.get()).setAttribute(SdnVeAttributes.PUBLIC_CIDR, publicCidr);
                ((EntityLocal) container.get()).setAttribute(SdnVeAttributes.PUBLIC_ADDRESS, publicAddress);
            }

            return address;
        } finally {
            Tasks.resetBlockingDetails();
        }
    }

}
