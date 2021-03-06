package org.zstack.test.integration.network.l3network.ipv6

import org.springframework.http.HttpEntity
import org.zstack.kvm.KVMAgentCommands
import org.zstack.kvm.KVMSecurityGroupBackend
import org.zstack.network.securitygroup.APIAddSecurityGroupRuleMsg
import org.zstack.network.securitygroup.RuleTO
import org.zstack.network.securitygroup.SecurityGroupMembersTO
import org.zstack.network.securitygroup.SecurityGroupRuleProtocolType
import org.zstack.network.securitygroup.SecurityGroupRuleTO
import org.zstack.network.securitygroup.SecurityGroupRuleType
import org.zstack.network.service.flat.FlatDhcpBackend
import org.zstack.sdk.*
import org.zstack.test.integration.network.l3network.Env
import org.zstack.test.integration.networkservice.provider.NetworkServiceProviderTest
import org.zstack.testlib.EnvSpec
import org.zstack.testlib.SubCase
import org.zstack.utils.gson.JSONObjectUtil
import org.zstack.utils.network.IPv6Constants

import java.util.stream.Collectors

import static java.util.Arrays.asList

/**
 * Created by shixin on 2018/09/26.
 */
class IPv6SecurityGroupCase extends SubCase {
    EnvSpec env

    @Override
    void clean() {
        env.delete()
    }

    @Override
    void setup() {
        useSpring(NetworkServiceProviderTest.springSpec)
    }
    @Override
    void environment() {
        env = Env.Ipv6FlatL3Network()
    }

    @Override
    void test() {
        env.create {
            testSecurityGroupValidator()
            testApplySecurityGroup()
            testDetachL3NetworkFromSecurityGroup()
            testSecurityGroupApplyNetworkServices()
            testChangeSecurityGroupRules()
            testDeleteSecurityGroup()
        }
    }

    void testSecurityGroupValidator() {
        L3NetworkInventory l3_statefull = env.inventoryByName("l3-Statefull-DHCP")
        L3NetworkInventory l3 = env.inventoryByName("l3")

        SecurityGroupInventory sg4 = createSecurityGroup {
            name = "SecurityGroup4"
            ipVersion = 4
        }

        SecurityGroupInventory sg6 = createSecurityGroup {
            name = "SecurityGroup6"
            ipVersion = 6
        }

        APIAddSecurityGroupRuleMsg.SecurityGroupRuleAO rule4 = new APIAddSecurityGroupRuleMsg.SecurityGroupRuleAO()
        rule4.allowedCidr = "192.168.0.1/24"
        rule4.type = SecurityGroupRuleType.Ingress.toString()
        rule4.protocol = SecurityGroupRuleProtocolType.TCP.toString()
        rule4.startPort = 100
        rule4.endPort = 200

        APIAddSecurityGroupRuleMsg.SecurityGroupRuleAO rule6 = new APIAddSecurityGroupRuleMsg.SecurityGroupRuleAO()
        rule6.allowedCidr = "2002::/64"
        rule6.type = SecurityGroupRuleType.Ingress.toString()
        rule6.protocol = SecurityGroupRuleProtocolType.TCP.toString()
        rule6.startPort = 100
        rule6.endPort = 200
        rule6.ipVersion = 6

        expect(AssertionError.class) {
            addSecurityGroupRule {
                delegate.securityGroupUuid = sg4.uuid
                delegate.rules = [rule6]
            }
        }

        expect(AssertionError.class) {
            addSecurityGroupRule {
                delegate.securityGroupUuid = sg6.uuid
                delegate.rules = [rule4]
            }
        }

        addSecurityGroupRule {
            delegate.securityGroupUuid = sg4.uuid
            delegate.rules = [rule4]
        }

        addSecurityGroupRule {
            delegate.securityGroupUuid = sg6.uuid
            delegate.rules = [rule6]
        }

        expect(AssertionError.class) {
            attachSecurityGroupToL3Network {
                securityGroupUuid = sg4.uuid
                l3NetworkUuid = l3_statefull.uuid
            }
        }

        expect(AssertionError.class) {
            attachSecurityGroupToL3Network {
                securityGroupUuid = sg6.uuid
                l3NetworkUuid = l3.uuid
            }
        }

        attachSecurityGroupToL3Network {
            securityGroupUuid = sg4.uuid
            l3NetworkUuid = l3.uuid
        }

        attachSecurityGroupToL3Network {
            securityGroupUuid = sg6.uuid
            l3NetworkUuid = l3_statefull.uuid
        }
    }

    void testApplySecurityGroup() {
        L3NetworkInventory l3_statefull = env.inventoryByName("l3-Statefull-DHCP")
        L3NetworkInventory l3 = env.inventoryByName("l3")
        InstanceOfferingInventory offering = env.inventoryByName("instanceOffering")
        ImageInventory image = env.inventoryByName("image1")
        HostInventory host = env.inventoryByName("kvm-1")

        VmInstanceInventory vm = createVmInstance {
            name = "vm-sg"
            instanceOfferingUuid = offering.uuid
            imageUuid = image.uuid
            l3NetworkUuids = asList(l3_statefull.uuid)
            hostUuid = host.uuid
        }
        VmNicInventory nic = vm.getVmNics()[0]
        attachL3NetworkToVmNic {
            vmNicUuid = nic.uuid
            l3NetworkUuid = l3.uuid
        }

        SecurityGroupInventory sg4 = querySecurityGroup {
            conditions=["name=SecurityGroup4"]
        }[0]
        SecurityGroupInventory sg6 = querySecurityGroup {
            conditions=["name=SecurityGroup6"]
        }[0]

        addVmNicToSecurityGroup {
            securityGroupUuid = sg4.uuid
            vmNicUuids = [nic.uuid]
        }
        addVmNicToSecurityGroup {
            securityGroupUuid = sg6.uuid
            vmNicUuids = [nic.uuid]
        }

        KVMAgentCommands.ApplySecurityGroupRuleCmd cmd = null
        env.afterSimulator(KVMSecurityGroupBackend.SECURITY_GROUP_APPLY_RULE_PATH) { rsp, HttpEntity<String> e ->
            cmd = JSONObjectUtil.toObject(e.body, KVMAgentCommands.ApplySecurityGroupRuleCmd.class)
            return rsp
        }

        changeSecurityGroupState {
            uuid = sg4.uuid
            stateEvent = "disable"
        }

        retryInSecs {
            assert cmd != null
            assert cmd.ipv6RuleTOs.size() == 1
            SecurityGroupRuleTO rule6 = cmd.ipv6RuleTOs.get(0)
            assert rule6.actionCode == SecurityGroupRuleTO.ACTION_CODE_APPLY_RULE
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule4 = cmd.ruleTOs.get(0)
            assert rule4.actionCode == SecurityGroupRuleTO.ACTION_CODE_DELETE_CHAIN
        }

        cmd == null
        changeSecurityGroupState {
            uuid = sg4.uuid
            stateEvent = "enable"
        }
        retryInSecs {
            assert cmd != null
            assert cmd.ipv6RuleTOs.size() == 1
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule6 = cmd.ipv6RuleTOs.get(0)
            assert rule6.actionCode == SecurityGroupRuleTO.ACTION_CODE_APPLY_RULE
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule4 = cmd.ruleTOs.get(0)
            assert rule4.actionCode == SecurityGroupRuleTO.ACTION_CODE_APPLY_RULE
        }

        cmd == null
        changeSecurityGroupState {
            uuid = sg6.uuid
            stateEvent = "disable"
        }
        retryInSecs {
            assert cmd != null
            assert cmd.ipv6RuleTOs.size() == 1
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule6 = cmd.ipv6RuleTOs.get(0)
            assert rule6.actionCode == SecurityGroupRuleTO.ACTION_CODE_DELETE_CHAIN
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule4 = cmd.ruleTOs.get(0)
            assert rule4.actionCode == SecurityGroupRuleTO.ACTION_CODE_APPLY_RULE
        }

        cmd == null
        changeSecurityGroupState {
            uuid = sg6.uuid
            stateEvent = "enable"
        }
        retryInSecs {
            assert cmd != null
            assert cmd.ipv6RuleTOs.size() == 1
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule6 = cmd.ipv6RuleTOs.get(0)
            assert rule6.actionCode == SecurityGroupRuleTO.ACTION_CODE_APPLY_RULE
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule4 = cmd.ruleTOs.get(0)
            assert rule4.actionCode == SecurityGroupRuleTO.ACTION_CODE_APPLY_RULE
        }
    }

    void testDetachL3NetworkFromSecurityGroup() {
        L3NetworkInventory l3_statefull = env.inventoryByName("l3-Statefull-DHCP")
        L3NetworkInventory l3 = env.inventoryByName("l3")

        SecurityGroupInventory sg4 = querySecurityGroup {
            conditions=["name=SecurityGroup4"]
        }[0]
        SecurityGroupInventory sg6 = querySecurityGroup {
            conditions=["name=SecurityGroup6"]
        }[0]
        VmInstanceInventory vm = queryVmInstance {conditions=["name=vm-sg"]}[0]
        VmNicInventory nic = vm.getVmNics()[0]

        KVMAgentCommands.ApplySecurityGroupRuleCmd cmd = null
        env.afterSimulator(KVMSecurityGroupBackend.SECURITY_GROUP_APPLY_RULE_PATH) { rsp, HttpEntity<String> e ->
            cmd = JSONObjectUtil.toObject(e.body, KVMAgentCommands.ApplySecurityGroupRuleCmd.class)
            return rsp
        }

        detachSecurityGroupFromL3Network {
            l3NetworkUuid = l3.uuid
            securityGroupUuid = sg4.uuid
        }

        retryInSecs {
            assert cmd != null
            assert cmd.ipv6RuleTOs.size() == 1
            SecurityGroupRuleTO rule6 = cmd.ipv6RuleTOs.get(0)
            assert rule6.actionCode == SecurityGroupRuleTO.ACTION_CODE_APPLY_RULE
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule4 = cmd.ruleTOs.get(0)
            assert rule4.actionCode == SecurityGroupRuleTO.ACTION_CODE_DELETE_CHAIN
        }

        cmd == null
        attachSecurityGroupToL3Network {
            securityGroupUuid = sg4.uuid
            l3NetworkUuid = l3.uuid
        }
        addVmNicToSecurityGroup {
            securityGroupUuid = sg4.uuid
            vmNicUuids = [nic.uuid]
        }
        retryInSecs {
            assert cmd != null
            assert cmd.ipv6RuleTOs.size() == 1
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule6 = cmd.ipv6RuleTOs.get(0)
            assert rule6.actionCode == SecurityGroupRuleTO.ACTION_CODE_APPLY_RULE
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule4 = cmd.ruleTOs.get(0)
            assert rule4.actionCode == SecurityGroupRuleTO.ACTION_CODE_APPLY_RULE
        }

        cmd == null
        detachSecurityGroupFromL3Network {
            l3NetworkUuid = l3_statefull.uuid
            securityGroupUuid = sg6.uuid
        }
        retryInSecs {
            assert cmd != null
            assert cmd.ipv6RuleTOs.size() == 1
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule6 = cmd.ipv6RuleTOs.get(0)
            assert rule6.actionCode == SecurityGroupRuleTO.ACTION_CODE_DELETE_CHAIN
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule4 = cmd.ruleTOs.get(0)
            assert rule4.actionCode == SecurityGroupRuleTO.ACTION_CODE_APPLY_RULE
        }

        cmd == null
        attachSecurityGroupToL3Network {
            securityGroupUuid = sg6.uuid
            l3NetworkUuid = l3_statefull.uuid
        }
        addVmNicToSecurityGroup {
            securityGroupUuid = sg6.uuid
            vmNicUuids = [nic.uuid]
        }
        retryInSecs {
            assert cmd != null
            assert cmd.ipv6RuleTOs.size() == 1
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule6 = cmd.ipv6RuleTOs.get(0)
            assert rule6.actionCode == SecurityGroupRuleTO.ACTION_CODE_APPLY_RULE
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule4 = cmd.ruleTOs.get(0)
            assert rule4.actionCode == SecurityGroupRuleTO.ACTION_CODE_APPLY_RULE
        }
    }

    void testSecurityGroupApplyNetworkServices() {
        HostInventory host = env.inventoryByName("kvm-1")

        VmInstanceInventory vm = queryVmInstance {
            conditions = ["name=vm-sg"]
        }[0]
        VmNicInventory nic = vm.getVmNics()[0]

        KVMAgentCommands.ApplySecurityGroupRuleCmd cmd = null
        env.afterSimulator(KVMSecurityGroupBackend.SECURITY_GROUP_APPLY_RULE_PATH) { rsp, HttpEntity<String> e ->
            cmd = JSONObjectUtil.toObject(e.body, KVMAgentCommands.ApplySecurityGroupRuleCmd.class)
            return rsp
        }

        rebootVmInstance {
            uuid = vm.uuid
        }
        retryInSecs {
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule = cmd.ruleTOs.get(0)
            assert rule.vmNicUuid == nic.uuid
            assert rule.rules.size() == 1
            assert rule.securityGroupBaseRules.size() == 2
            RuleTO ruleTo = rule.rules.get(0)
            assert ruleTo.ipVersion == IPv6Constants.IPv4
            assert ruleTo.allowedCidr == "192.168.0.1/24"

            assert cmd.ipv6RuleTOs.size() == 1
            rule = cmd.ipv6RuleTOs.get(0)
            assert rule.vmNicUuid == nic.uuid
            assert rule.rules.size() == 1
            assert rule.securityGroupBaseRules.size() == 2
            ruleTo = rule.rules.get(0)
            assert ruleTo.ipVersion == IPv6Constants.IPv6
            assert ruleTo.allowedCidr == "2002::/64"
        }

        KVMAgentCommands.RefreshAllRulesOnHostCmd rcmd = null
        env.afterSimulator(KVMSecurityGroupBackend.SECURITY_GROUP_REFRESH_RULE_ON_HOST_PATH) { rsp, HttpEntity<String> e ->
            rcmd = JSONObjectUtil.toObject(e.body, KVMAgentCommands.RefreshAllRulesOnHostCmd.class)
            return rsp
        }
        reconnectHost {
            uuid = host.uuid
        }
        retryInSecs {
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule = cmd.ruleTOs.get(0)
            assert rule.vmNicUuid == nic.uuid
            assert rule.rules.size() == 1
            assert rule.securityGroupBaseRules.size() == 2
            RuleTO ruleTo = rule.rules.get(0)
            assert ruleTo.ipVersion == IPv6Constants.IPv4
            assert ruleTo.allowedCidr == "192.168.0.1/24"

            assert cmd.ipv6RuleTOs.size() == 1
            rule = cmd.ipv6RuleTOs.get(0)
            assert rule.vmNicUuid == nic.uuid
            assert rule.rules.size() == 1
            assert rule.securityGroupBaseRules.size() == 2
            ruleTo = rule.rules.get(0)
            assert ruleTo.ipVersion == IPv6Constants.IPv6
            assert ruleTo.allowedCidr == "2002::/64"
        }

        SecurityGroupInventory sg4 = querySecurityGroup {
            conditions = ["name=SecurityGroup4"]
        }[0]
        SecurityGroupInventory sg6 = querySecurityGroup {
            conditions = ["name=SecurityGroup6"]
        }[0]

        cmd = null
        deleteVmNicFromSecurityGroup {
            securityGroupUuid = sg4.uuid
            vmNicUuids = [nic.uuid]
        }

        rebootVmInstance {
            uuid = vm.uuid
        }
        retryInSecs {
            assert cmd.ruleTOs == null

            assert cmd.ipv6RuleTOs.size() == 1
            SecurityGroupRuleTO rule = cmd.ipv6RuleTOs.get(0)
            assert rule.vmNicUuid == nic.uuid
            assert rule.rules.size() == 1
            assert rule.securityGroupBaseRules.size() == 2
            RuleTO ruleTo = rule.rules.get(0)
            assert ruleTo.ipVersion == IPv6Constants.IPv6
            assert ruleTo.allowedCidr == "2002::/64"
        }

        rcmd = null
        reconnectHost {
            uuid = host.uuid
        }
        retryInSecs {
            assert cmd.ruleTOs == null

            assert cmd.ipv6RuleTOs.size() == 1
            SecurityGroupRuleTO rule = cmd.ipv6RuleTOs.get(0)
            assert rule.vmNicUuid == nic.uuid
            assert rule.rules.size() == 1
            assert rule.securityGroupBaseRules.size() == 2
            RuleTO ruleTo = rule.rules.get(0)
            assert ruleTo.ipVersion == IPv6Constants.IPv6
            assert ruleTo.allowedCidr == "2002::/64"
        }
    }

    void testChangeSecurityGroupRules() {
        L3NetworkInventory l3_statefull = env.inventoryByName("l3-Statefull-DHCP")
        L3NetworkInventory l3 = env.inventoryByName("l3")

        VmInstanceInventory vm = queryVmInstance {
            conditions = ["name=vm-sg"]
        }[0]
        VmNicInventory nic = vm.getVmNics()[0]
        SecurityGroupInventory sg4 = querySecurityGroup {
            conditions=["name=SecurityGroup4"]
        }[0]
        SecurityGroupInventory sg6 = querySecurityGroup {
            conditions=["name=SecurityGroup6"]
        }[0]

        addVmNicToSecurityGroup {
            securityGroupUuid = sg4.uuid
            vmNicUuids = [nic.uuid]
        }

        APIAddSecurityGroupRuleMsg.SecurityGroupRuleAO rule4 = new APIAddSecurityGroupRuleMsg.SecurityGroupRuleAO()
        rule4.allowedCidr = "192.168.0.1/24"
        rule4.type = SecurityGroupRuleType.Ingress.toString()
        rule4.protocol = SecurityGroupRuleProtocolType.ICMP.toString()
        rule4.startPort = 0
        rule4.endPort = 3

        APIAddSecurityGroupRuleMsg.SecurityGroupRuleAO rule41 = new APIAddSecurityGroupRuleMsg.SecurityGroupRuleAO()
        rule41.allowedCidr = "192.168.1.1/24"
        rule41.type = SecurityGroupRuleType.Ingress.toString()
        rule41.protocol = SecurityGroupRuleProtocolType.UDP.toString()
        rule41.startPort = 100
        rule41.endPort = 200

        addSecurityGroupRule {
            delegate.securityGroupUuid = sg4.uuid
            delegate.rules = [rule4, rule41]
        }

        sg4 = querySecurityGroup {
            conditions=["name=SecurityGroup4"]
        }[0]
        sg4.rules.size() == 3
        List<SecurityGroupRuleInventory> rules = sg4.rules
        SecurityGroupRuleInventory rule1 = rules.stream().filter{r -> r.protocol == SecurityGroupRuleProtocolType.ICMP.toString()}.collect(Collectors.toList()).get(0)
        deleteSecurityGroupRule {
            ruleUuids = asList(rule1.uuid)
        }

        APIAddSecurityGroupRuleMsg.SecurityGroupRuleAO rule6 = new APIAddSecurityGroupRuleMsg.SecurityGroupRuleAO()
        rule6.allowedCidr = "2002::/64"
        rule6.type = SecurityGroupRuleType.Egress.toString()
        rule6.protocol = SecurityGroupRuleProtocolType.ICMP.toString()
        rule6.ipVersion = 6
        rule6.startPort = 0
        rule6.endPort = 3

        APIAddSecurityGroupRuleMsg.SecurityGroupRuleAO rule61 = new APIAddSecurityGroupRuleMsg.SecurityGroupRuleAO()
        rule61.allowedCidr = "2003::/64"
        rule61.type = SecurityGroupRuleType.Egress.toString()
        rule61.protocol = SecurityGroupRuleProtocolType.UDP.toString()
        rule61.startPort = 100
        rule61.startPort = 200
        rule61.ipVersion = 6

        addSecurityGroupRule {
            delegate.securityGroupUuid = sg6.uuid
            delegate.rules = [rule6, rule61]
        }
        sg6 = querySecurityGroup {
            conditions=["name=SecurityGroup6"]
        }[0]
        sg6.rules.size() == 3
        rules = sg6.rules
        rule1 = rules.stream().filter{r -> r.protocol == SecurityGroupRuleProtocolType.TCP.toString()}.collect(Collectors.toList()).get(0)
        deleteSecurityGroupRule {
            ruleUuids = asList(rule1.uuid)
        }
    }

    void testDeleteSecurityGroup() {
        SecurityGroupInventory sg4 = querySecurityGroup {
            conditions = ["name=SecurityGroup4"]
        }[0]

        KVMAgentCommands.ApplySecurityGroupRuleCmd cmd = null
        env.afterSimulator(KVMSecurityGroupBackend.SECURITY_GROUP_APPLY_RULE_PATH) { rsp, HttpEntity<String> e ->
            cmd = JSONObjectUtil.toObject(e.body, KVMAgentCommands.ApplySecurityGroupRuleCmd.class)
            return rsp
        }

        deleteSecurityGroup {
            uuid = sg4.uuid
        }

        retryInSecs {
            assert cmd != null
            assert cmd.ipv6RuleTOs.size() == 1
            SecurityGroupRuleTO rule6 = cmd.ipv6RuleTOs.get(0)
            assert rule6.actionCode == SecurityGroupRuleTO.ACTION_CODE_APPLY_RULE
            assert cmd.ruleTOs.size() == 1
            SecurityGroupRuleTO rule4 = cmd.ruleTOs.get(0)
            assert rule4.actionCode == SecurityGroupRuleTO.ACTION_CODE_DELETE_CHAIN
        }
    }
}

