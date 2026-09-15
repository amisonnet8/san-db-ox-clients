import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { matchJson, parseLiteral, stringifyLiteral } from "./match.js";

describe("matchJson", () => {
  test("extra actual keys are ignored", () => {
    const expect = parseLiteral('{"a":1}');
    const actual = parseLiteral('{"a":1,"b":2}');
    assert.equal(matchJson(expect, actual), null);
  });

  test("a missing key is a mismatch", () => {
    const expect = parseLiteral('{"a":1,"b":2}');
    const actual = parseLiteral('{"a":1}');
    assert.equal(matchJson(expect, actual), "$.b");
  });

  test("arrays require exact length and order", () => {
    assert.equal(matchJson(parseLiteral("[1,2,3]"), parseLiteral("[1,2,3]")), null);
    assert.equal(matchJson(parseLiteral("[1,2,3]"), parseLiteral("[1,2]")), "$");
    assert.equal(matchJson(parseLiteral("[1,2]"), parseLiteral("[2,1]")), "$[0]");
  });

  test("numbers compare by literal token: 88 never matches 88.0", () => {
    assert.equal(matchJson(parseLiteral("88"), parseLiteral("88.0")), "$");
    assert.equal(matchJson(parseLiteral("88.0"), parseLiteral("88.0")), null);
  });

  test("large integers stay uncorrupted", () => {
    const token = "9223372036854775807";
    assert.equal(matchJson(parseLiteral(token), parseLiteral(token)), null);
    assert.equal(
      matchJson(parseLiteral(token), parseLiteral(`${token.slice(0, -1)}6`)),
      "$",
    );
  });

  test("strings and numbers never cross-match", () => {
    assert.equal(matchJson(parseLiteral('"88"'), parseLiteral("88")), "$");
    assert.equal(matchJson(parseLiteral("88"), parseLiteral('"88"')), "$");
  });

  test("null vs a missing field", () => {
    assert.equal(matchJson(parseLiteral('{"a":null}'), parseLiteral("{}")), "$.a");
  });

  test("nested partial match", () => {
    const expect = parseLiteral('{"a":{"b":1}}');
    const actual = parseLiteral('{"a":{"b":1,"c":"extra"},"d":"extra"}');
    assert.equal(matchJson(expect, actual), null);
  });

  test("exact equality still matches", () => {
    const v = parseLiteral('{"a":[1,2.5,"x",null,true,false]}');
    assert.equal(matchJson(v, v), null);
  });
});

describe("parseLiteral / stringifyLiteral round-trip", () => {
  test("reproduces a case file's number tokens byte-for-byte", () => {
    const text = '{"big":9223372036854775807,"real":88.0,"inf":9e999,"ninf":-9e999}';
    assert.equal(stringifyLiteral(parseLiteral(text)), text);
  });
});
