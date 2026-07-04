package org.jarvis.visionsecurity.service;

import org.jarvis.visionsecurity.model.CvBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OcrServiceParseTsvTest {

    private static final String HEADER = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num"
            + "\tleft\ttop\twidth\theight\tconf\ttext";

    @Test
    void groupsWordsByLineAndUnionsBoundingBoxes() {
        String tsv = String.join("\n",
                HEADER,
                "5\t1\t1\t1\t1\t1\t10\t20\t40\t30\t96.0\tHello",
                "5\t1\t1\t1\t1\t2\t60\t22\t50\t28\t90.0\tWorld",
                "5\t1\t1\t1\t2\t1\t10\t60\t100\t30\t80.0\tNext");

        List<CvBlock> blocks = OcrService.parseTsv(tsv);

        assertThat(blocks).hasSize(2);
        CvBlock line1 = blocks.get(0);
        assertThat(line1.text()).isEqualTo("Hello World");
        assertThat(line1.confidence()).isEqualTo(93.0);
        assertThat(line1.bbox().x()).isEqualTo(10);
        assertThat(line1.bbox().y()).isEqualTo(20);
        assertThat(line1.bbox().width()).isEqualTo(100); // 60 + 50 - 10
        assertThat(line1.bbox().height()).isEqualTo(30); // 50 (max bottom) - 20 (min top)
        CvBlock line2 = blocks.get(1);
        assertThat(line2.text()).isEqualTo("Next");
        assertThat(line2.confidence()).isEqualTo(80.0);
        assertThat(line2.bbox().width()).isEqualTo(100);
    }

    @Test
    void ignoresNonWordRowsAndBlankText() {
        String tsv = String.join("\n",
                HEADER,
                "1\t1\t0\t0\t0\t0\t0\t0\t640\t200\t-1\t",
                "2\t1\t1\t0\t0\t0\t21\t25\t258\t127\t-1\t",
                "5\t1\t1\t1\t1\t1\t10\t20\t40\t30\t96.0\t   ",
                "5\t1\t1\t1\t1\t2\t60\t22\t50\t28\t90.0\tValid");

        List<CvBlock> blocks = OcrService.parseTsv(tsv);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo("Valid");
    }

    @Test
    void returnsEmptyWhenTsvIsBlankOrHeaderOnly() {
        assertThat(OcrService.parseTsv(null)).isEmpty();
        assertThat(OcrService.parseTsv("")).isEmpty();
        assertThat(OcrService.parseTsv(HEADER)).isEmpty();
    }

    @Test
    void skipsRowsThatHaveTooFewColumns() {
        String tsv = String.join("\n",
                HEADER,
                "5\t1\t1\t1\t1\t1\toops",
                "5\t1\t1\t1\t1\t2\t60\t22\t50\t28\t91.0\tOK");

        List<CvBlock> blocks = OcrService.parseTsv(tsv);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo("OK");
    }

    @Test
    void confidenceMinusOneIsExcludedFromAverage() {
        String tsv = String.join("\n",
                HEADER,
                "5\t1\t1\t1\t1\t1\t10\t20\t40\t30\t-1\tlow",
                "5\t1\t1\t1\t1\t2\t60\t22\t50\t28\t80.0\thigh");

        List<CvBlock> blocks = OcrService.parseTsv(tsv);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).confidence()).isEqualTo(80.0);
    }
}
