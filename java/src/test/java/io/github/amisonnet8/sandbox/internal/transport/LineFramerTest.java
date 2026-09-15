package io.github.amisonnet8.sandbox.internal.transport;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LineFramerTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void multiple_lines_in_one_chunk() {
        LineFramer f = new LineFramer();
        f.push(b("one\ntwo\nthr"));
        assertArrayEquals(b("one"), f.takeLine());
        assertArrayEquals(b("two"), f.takeLine());
        assertNull(f.takeLine()); // "thr" is a partial line
    }

    @Test
    void one_line_split_across_chunks() {
        LineFramer f = new LineFramer();
        f.push(b("hel"));
        assertNull(f.takeLine());
        f.push(b("lo\n"));
        assertArrayEquals(b("hello"), f.takeLine());
    }

    @Test
    void lone_cr_does_not_split_a_line() {
        LineFramer f = new LineFramer();
        f.push(b("a\rb\n"));
        assertArrayEquals(b("a\rb"), f.takeLine());
    }

    @Test
    void exactly_max_line_bytes_is_accepted() {
        LineFramer f = new LineFramer();
        byte[] line = new byte[LineFramer.MAX_LINE_BYTES + 1];
        java.util.Arrays.fill(line, (byte) 'x');
        line[LineFramer.MAX_LINE_BYTES] = '\n';
        f.push(line);
        byte[] got = f.takeLine();
        org.junit.jupiter.api.Assertions.assertEquals(LineFramer.MAX_LINE_BYTES, got.length);
    }

    @Test
    void one_byte_over_max_line_bytes_without_newline_is_terminal() {
        LineFramer f = new LineFramer();
        byte[] line = new byte[LineFramer.MAX_LINE_BYTES + 1];
        java.util.Arrays.fill(line, (byte) 'x');
        f.push(line);
        assertThrows(TransportException.class, f::takeLine);
    }

    @Test
    void trailing_partial_line_is_retained_not_emitted() {
        LineFramer f = new LineFramer();
        f.push(b("complete\npartial"));
        assertArrayEquals(b("complete"), f.takeLine());
        assertNull(f.takeLine());
        f.push(b(" line\n"));
        assertArrayEquals(b("partial line"), f.takeLine());
    }
}
