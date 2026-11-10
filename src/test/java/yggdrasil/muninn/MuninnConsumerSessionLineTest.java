package yggdrasil.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The observer's per-message line the session gate greps for. */
class MuninnConsumerSessionLineTest {

    private static final byte[] DEF = "{}".getBytes();

    @Test
    void nbirthCarriesSeqAndBdSeq() throws Exception {
        assertEquals("[OBSERVE] NBIRTH seq=0 bdSeq=7",
                MuninnConsumer.sessionLine("NBIRTH", SparkplugCodec.buildSessionNbirth(DEF, 7)));
    }

    @Test
    void ndeathCarriesBdSeqOnly() throws Exception {
        assertEquals("[OBSERVE] NDEATH bdSeq=7",
                MuninnConsumer.sessionLine("NDEATH", SparkplugCodec.buildNdeath(7)));
    }

    @Test
    void dataCarriesSeqOnly() throws Exception {
        assertEquals("[OBSERVE] NDATA seq=42",
                MuninnConsumer.sessionLine("NDATA", SparkplugCodec.buildNdata(List.of(), 42)));
    }
}
