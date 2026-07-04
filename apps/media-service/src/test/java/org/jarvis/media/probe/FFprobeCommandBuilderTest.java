package org.jarvis.media.probe;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FFprobeCommandBuilderTest {

    private final FFprobeCommandBuilder builder = new FFprobeCommandBuilder();

    @Test
    void buildsArgumentListWithJsonOutput() {
        List<String> args = builder.build("ffprobe", Path.of("/media/movie.mkv"));
        assertThat(args).startsWith("ffprobe");
        assertThat(args).contains("-print_format", "json", "-show_streams");
        assertThat(args).last().isEqualTo("/media/movie.mkv");
    }

    @Test
    void hostileFilenameStaysASingleArgumentNoShellInjection() {
        // a filename engineered to break a shell string — must remain ONE list element
        String hostile = "/media/movie; rm -rf $(echo /) `id`.mkv";
        List<String> args = builder.build("ffprobe", Path.of(hostile));

        assertThat(args).contains(hostile);
        // the dangerous text appears exactly once, as one whole element, never split
        long occurrences = args.stream().filter(a -> a.equals(hostile)).count();
        assertThat(occurrences).isEqualTo(1);
        assertThat(args).noneMatch(a -> a.equals("rm") || a.equals("-rf"));
    }
}
