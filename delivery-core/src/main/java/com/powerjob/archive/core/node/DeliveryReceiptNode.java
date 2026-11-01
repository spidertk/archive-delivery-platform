package com.powerjob.archive.core.node;

import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.repository.OutputPackageRepository;
import org.springframework.stereotype.Component;

@Component
public class DeliveryReceiptNode implements NodeHandler {
    private final OutputPackageRepository outputs;

    public DeliveryReceiptNode(OutputPackageRepository outputs) {
        this.outputs = outputs;
    }

    @Override
    public String type() { return "delivery.receipt"; }

    @Override
    public NodeResult execute(NodeContext context) {
        String receipt = context.output().id() + ":" + context.output().manifest()
                .required("artifacts").required(0).required("sha256").asText();
        outputs.updatePath(context.output().id(), "receipt_id", receipt);
        return NodeResult.of(null, "DELIVERED", context.output().manifest());
    }
}
