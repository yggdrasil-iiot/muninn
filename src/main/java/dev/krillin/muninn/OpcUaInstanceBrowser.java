package dev.krillin.muninn;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.eclipse.tahu.message.model.MetricDataType;

/**
 * Live shell (Milo 1.1.8): FAITHFULLY sample a governed equipment INSTANCE's member
 * Variables into pure {@link SampledMember} facts (name + resolved Tahu {@link MetricDataType}
 * + live value). Mirrors mimir's {@code OpcUaMixerBrowser} browse + DataType-read idioms; the
 * difference is that muninn browses an INSTANCE and additionally reads each member's live VALUE
 * (and does NOT need EURange).
 *
 * <p>No business logic and no test/omit hooks live here (CF-E). The {@code --inject-bogus}
 * projection is applied by the caller ({@link MuninnMain}), never in this browser.
 */
public final class OpcUaInstanceBrowser {

    // OPC-UA standard NodeIds (numeric, ns=0)
    private static final NodeId HAS_COMPONENT = new NodeId(0, 47);

    private static final UInteger NODECLASS_ALL = Unsigned.uint(0xFF);
    private static final UInteger RESULT_ALL    = Unsigned.uint(0x3F);

    private OpcUaInstanceBrowser() {
    }

    /**
     * Sample the declared member Variables of the instance node whose NodeId string identifier
     * (what follows {@code s=}) is {@code instanceNodeIdent} in namespace {@code nsUri}.
     *
     * @param instanceNodeIdent the NodeId string identifier (e.g. {@code "Line1/Mixer1"}), NOT the
     *                          browseName ({@code "Mixer1"}) — the browseName resolves to no node.
     */
    public static List<SampledMember> sample(OpcUaClient client, String nsUri, String instanceNodeIdent)
            throws Exception {
        NamespaceTable nt = client.getNamespaceTable();
        UShort ns = nt.getIndex(nsUri);
        if (ns == null) {
            // CF-D: fail loudly rather than NPE on a null namespace index.
            throw new IllegalStateException("namespace URI '" + nsUri + "' not found on server");
        }
        NodeId instance = new NodeId(ns, instanceNodeIdent);

        List<SampledMember> samples = new ArrayList<>();

        BrowseResult res = client.browse(new BrowseDescription(
                instance, BrowseDirection.Forward, HAS_COMPONENT, false, NODECLASS_ALL, RESULT_ALL));
        for (ReferenceDescription r : safeRefs(res)) {
            if (r.getNodeClass() != NodeClass.Variable) continue;
            String name = r.getBrowseName().getName();
            NodeId memberNode = r.getNodeId().toNodeId(nt).orElse(null);
            if (memberNode == null) continue;

            int dataTypeId = readDataTypeId(client, memberNode);
            MetricDataType type = UaMetricTypes.of(dataTypeId); // CF-V
            Object value = client.readValue(0.0, TimestampsToReturn.Both, memberNode)
                    .getValue().getValue();

            samples.add(new SampledMember(name, type, value));
        }
        return samples;
    }

    /** Read the member's DataType attribute → numeric NodeId identifier. */
    private static int readDataTypeId(OpcUaClient client, NodeId memberNode) throws Exception {
        ReadValueId rvid = new ReadValueId(
                memberNode, AttributeId.DataType.uid(), null, QualifiedName.NULL_VALUE);
        var resp = client.read(0.0, TimestampsToReturn.Neither, List.of(rvid));
        DataValue dtDv = resp.getResults()[0];
        NodeId dtNode = (NodeId) dtDv.getValue().getValue();
        return ((UInteger) dtNode.getIdentifier()).intValue();
    }

    private static ReferenceDescription[] safeRefs(BrowseResult res) {
        ReferenceDescription[] refs = res != null ? res.getReferences() : null;
        return refs != null ? refs : new ReferenceDescription[0];
    }
}
