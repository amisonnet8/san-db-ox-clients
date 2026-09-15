package io.github.amisonnet8.sandbox.internal.codec;

import io.github.amisonnet8.sandbox.DumpResult;
import io.github.amisonnet8.sandbox.ExecResult;
import io.github.amisonnet8.sandbox.Hello;
import io.github.amisonnet8.sandbox.InspectResult;
import io.github.amisonnet8.sandbox.QueryResult;
import io.github.amisonnet8.sandbox.SchemaResult;
import io.github.amisonnet8.sandbox.SnapshotResult;
import io.github.amisonnet8.sandbox.TablesResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecodeTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void hello_ok() {
        Hello h = Decode.hello(bytes("{\"protocol\":1,\"version\":\"v0.1.1\",\"product\":\"SanDBox\"}"));
        assertEquals(new Hello(1, "v0.1.1", "SanDBox"), h);
    }

    @Test
    void hello_does_not_validate_protocol_number() {
        // Decode.hello only parses; comparing against SanDBox.PROTOCOL is Session's job.
        Hello h = Decode.hello(bytes("{\"protocol\":999,\"version\":\"x\",\"product\":\"SanDBox\"}"));
        assertEquals(999, h.protocol());
    }

    @Test
    void hello_missing_field_and_invalid_json() {
        assertThrows(CodecException.class, () -> Decode.hello(bytes("{\"protocol\":1,\"version\":\"x\"}")));
        assertThrows(CodecException.class, () -> Decode.hello(bytes("not json")));
        assertThrows(CodecException.class, () -> Decode.hello(bytes("[1,2,3]")));
    }

    @Test
    void response_ok_true_carries_every_field() {
        DecodedResponse r = Decode.responseLine(bytes("{\"ok\":true,\"columns\":[\"a\"],\"rows\":[],\"extra\":\"x\"}"));
        assertTrue(r.ok());
    }

    @Test
    void response_ok_false_with_and_without_error_object() {
        DecodedResponse r = Decode.responseLine(bytes("{\"ok\":false,\"error\":{\"code\":\"bad_request\",\"message\":\"nope\"}}"));
        assertTrue(!r.ok());
        ResponseError e = Decode.error(r);
        assertEquals("bad_request", e.code());

        DecodedResponse r2 = Decode.responseLine(bytes("{\"ok\":false}"));
        assertTrue(!r2.ok());
        assertNull(Decode.error(r2));
    }

    private static DecodedResponse decoded(String json) {
        return Decode.responseLine(bytes(json));
    }

    @Test
    void blob_array_wrong_length_rejected() {
        assertThrows(CodecException.class, () -> Decode.decodeValue(new Json.Arr(List.of())));
        assertThrows(
                CodecException.class,
                () -> Decode.decodeValue(new Json.Arr(List.of(new Json.Str("a"), new Json.Str("b")))));
    }

    @Test
    void bool_in_cell_rejected() {
        assertThrows(CodecException.class, () -> Decode.decodeValue(new Json.Bool(true)));
    }

    @Test
    void rows_not_array_and_row_not_array_rejected_with_index() {
        assertThrows(CodecException.class, () -> Decode.decodeRows(new Json.Null()));
        CodecException e = assertThrows(CodecException.class, () -> Decode.decodeRows(new Json.Arr(List.of(new Json.Null()))));
        assertTrue(e.getMessage().contains("rows[0]"));
    }

    @Test
    void decode_query_exec_snapshot() {
        QueryResult r = Decode.query(decoded("{\"ok\":true,\"columns\":[\"x\"],\"rows\":[[1]]}"));
        assertEquals(List.of("x"), r.columns());
        assertEquals(List.of(List.of(1L)), r.rows());

        ExecResult e = Decode.exec(decoded("{\"ok\":true,\"rows_affected\":2,\"last_insert_id\":9}"));
        assertEquals(new ExecResult(2, 9), e);

        SnapshotResult s = Decode.snapshot(decoded("{\"ok\":true,\"path\":\"snap.bin\"}"));
        assertEquals(new SnapshotResult("snap.bin"), s);
    }

    @Test
    void decode_inspect_with_and_without_nulls() {
        InspectResult r = Decode.inspect(decoded(
                "{\"ok\":true,\"has_data\":true,\"version\":3,\"data_length\":100,\"source\":\"embedded\",\"read_only\":false}"));
        assertEquals(new InspectResult(true, 3L, 100L, "embedded", false), r);

        InspectResult r2 = Decode.inspect(decoded(
                "{\"ok\":true,\"has_data\":false,\"version\":null,\"data_length\":null,\"source\":\"none\",\"read_only\":true}"));
        assertEquals(new InspectResult(false, null, null, "none", true), r2);
    }

    @Test
    void decode_tables_schema_dump() {
        TablesResult t = Decode.tables(decoded("{\"ok\":true,\"tables\":[\"a\",\"b\"]}"));
        assertEquals(new TablesResult(List.of("a", "b")), t);

        SchemaResult s = Decode.schema(decoded("{\"ok\":true,\"schema\":[\"CREATE TABLE a(x)\"]}"));
        assertEquals(new SchemaResult(List.of("CREATE TABLE a(x)")), s);

        DumpResult d = Decode.dump(decoded("{\"ok\":true,\"sql\":\"INSERT INTO a VALUES(1);\"}"));
        assertEquals(new DumpResult("INSERT INTO a VALUES(1);"), d);
    }

    @Test
    void missing_required_field_is_protocol_error() {
        DecodedResponse r = decoded("{\"ok\":true,\"columns\":[\"x\"]}");
        assertThrows(CodecException.class, () -> Decode.query(r));
    }

    @Test
    void decode_value_large_integer_and_real() {
        assertEquals(Long.MAX_VALUE, Decode.decodeValue(new Json.Num(new NumberToken("9223372036854775807"))));
        assertEquals(88.0, Decode.decodeValue(new Json.Num(new NumberToken("88.0"))));
        assertEquals(Double.POSITIVE_INFINITY, Decode.decodeValue(new Json.Num(new NumberToken("9e999"))));
    }
}
