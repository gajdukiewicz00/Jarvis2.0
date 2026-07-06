package org.jarvis.swarm.process;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MavenFailureParserTest {

    // A representative Maven Surefire console/text-report excerpt: one assertion
    // failure and one error, followed by the "Results :" summary section that this
    // parser actually reads (the per-test "<<< FAILURE!" lines above it are ignored —
    // they're covered instead by TestOutputSummarizer's aggregate count).
    private static final String SAMPLE_SUREFIRE_LOG = """
            -------------------------------------------------------
             T E S T S
            -------------------------------------------------------
            Running org.jarvis.swarm.task.AgentTaskStatusTest
            Tests run: 4, Failures: 1, Errors: 1, Skipped: 0, Time elapsed: 0.045 sec <<< FAILURE! - in org.jarvis.swarm.task.AgentTaskStatusTest
            invalidTransitionsAreRejected(org.jarvis.swarm.task.AgentTaskStatusTest)  Time elapsed: 0.01 sec  <<< FAILURE!
            org.opentest4j.AssertionFailedError: expected: <false> but was: <true>
            \tat org.jarvis.swarm.task.AgentTaskStatusTest.invalidTransitionsAreRejected(AgentTaskStatusTest.java:26)

            modelEnforcesTransitionsAndThrowsOnIllegalMove(org.jarvis.swarm.task.AgentTaskStatusTest)  Time elapsed: 0.002 sec  <<< ERROR!
            java.lang.NullPointerException
            \tat org.jarvis.swarm.task.AgentTaskStatusTest.modelEnforcesTransitionsAndThrowsOnIllegalMove(AgentTaskStatusTest.java:41)

            Results :

            Failed tests: \s
              AgentTaskStatusTest.invalidTransitionsAreRejected:26 expected: <false> but was: <true>

            Tests in error: \s
              AgentTaskStatusTest.modelEnforcesTransitionsAndThrowsOnIllegalMove:41 » NullPointer

            Tests run: 4, Failures: 1, Errors: 1, Skipped: 0
            """;

    @Test
    void parsesBothTheFailedAndErroredTestFromTheResultsSection() {
        List<TestFailure> failures = MavenFailureParser.parse(SAMPLE_SUREFIRE_LOG);

        assertThat(failures).hasSize(2);

        TestFailure failed = failures.get(0);
        assertThat(failed.testClass()).isEqualTo("AgentTaskStatusTest");
        assertThat(failed.testMethod()).isEqualTo("invalidTransitionsAreRejected");
        assertThat(failed.classAndMethod()).isEqualTo("AgentTaskStatusTest#invalidTransitionsAreRejected");
        assertThat(failed.message()).contains("expected: <false> but was: <true>");

        TestFailure errored = failures.get(1);
        assertThat(errored.testClass()).isEqualTo("AgentTaskStatusTest");
        assertThat(errored.testMethod()).isEqualTo("modelEnforcesTransitionsAndThrowsOnIllegalMove");
        assertThat(errored.message()).contains("NullPointer");
    }

    @Test
    void describeCombinesClassMethodAndMessageOnOneLine() {
        TestFailure failure = new TestFailure("FooTest", "testBar", "expected 1 but was 2");

        assertThat(failure.describe()).isEqualTo("FooTest#testBar: expected 1 but was 2");
    }

    @Test
    void describeOmitsTheColonWhenThereIsNoMessage() {
        TestFailure failure = new TestFailure("FooTest", "testBar", "");

        assertThat(failure.describe()).isEqualTo("FooTest#testBar");
    }

    @Test
    void fullyQualifiedClassNamesAreParsedCorrectly() {
        String output = """
                Results :

                Failed tests: \s
                  org.jarvis.swarm.FooTest.testBar:12 boom

                Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
                """;

        List<TestFailure> failures = MavenFailureParser.parse(output);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).testClass()).isEqualTo("org.jarvis.swarm.FooTest");
        assertThat(failures.get(0).testMethod()).isEqualTo("testBar");
    }

    @Test
    void returnsEmptyListWhenNoResultsSectionIsPresent() {
        assertThat(MavenFailureParser.parse("BUILD SUCCESS")).isEmpty();
        assertThat(MavenFailureParser.parse("")).isEmpty();
        assertThat(MavenFailureParser.parse(null)).isEmpty();
    }

    @Test
    void returnsEmptyListForNonMavenOutputLikeJestOrPytest() {
        String jest = "Tests:       1 failed, 2 skipped, 4 passed, 7 total\n";
        String pytest = "========= 5 passed, 1 failed, 2 skipped in 0.12s =========\n";

        assertThat(MavenFailureParser.parse(jest)).isEmpty();
        assertThat(MavenFailureParser.parse(pytest)).isEmpty();
    }
}
