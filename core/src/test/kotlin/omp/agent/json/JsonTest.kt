package omp.agent.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [Json.parse] against the shapes that matter and the ones it has to refuse.
 *
 * Every refusal below is a case where a lenient parser would answer with something plausible, and
 * the plausible answer is what a user would then believe: a truncated body read as a whole reply, a
 * 20-digit id rounded, a second document quietly dropped. Each is named rather than swept.
 */
class JsonTest {

    private fun refusal(text: String): JsonException {
        try {
            Json.parse(text)
        } catch (e: JsonException) {
            return e
        }
        fail("expected a JsonException for ${text.take(40)}")
        throw AssertionError("unreachable")
    }

    // ---- values ------------------------------------------------------------------------

    @Test
    fun readsTheSixKindsApart() {
        assertEquals(Json.Str("hi"), Json.parse("\"hi\""))
        assertEquals(Json.Bool(true), Json.parse("true"))
        assertEquals(Json.Bool(false), Json.parse("false"))
        assertEquals(Json.Null, Json.parse("null"))
        assertEquals(Json.Arr(emptyList()), Json.parse("[]"))
        assertEquals(Json.Obj(emptyMap()), Json.parse("{}"))
        assertEquals(Json.Arr(listOf(Json.Num(1.0, "1"))), Json.parse("[1]"))
    }

    @Test
    fun literalsAreNotStrings() {
        assertEquals(Json.Str("true"), Json.parse("\"true\""))
        assertEquals(Json.Str("null"), Json.parse("\"null\""))
        assertEquals(Json.Str("false"), Json.parse("\"false\""))
        assertEquals(Json.Str("1"), Json.parse("\"1\""))
    }

    @Test
    fun whitespaceAroundTheValueIsIgnored() {
        val text = " \t\r\n {\n \"a\" : [ 1 , 2 ]\r\n} \n"
        assertEquals("{\"a\":[1,2]}", Json.parse(text).toCompactString())
    }

    @Test
    fun keepsFieldOrder() {
        val obj = Json.parse("""{"b":1,"a":2,"c":3}""") as Json.Obj
        assertEquals(listOf("b", "a", "c"), obj.fields.keys.toList())
    }

    // ---- strings -----------------------------------------------------------------------

    @Test
    fun resolvesTheShortEscapes() {
        val value = (Json.parse(""""a\"b\\c\/d\be\ff\ng\rh\ti"""") as Json.Str).value
        assertEquals("a\"b\\c/d\bef\ng\rh\ti", value)
    }

    @Test
    fun foldsASurrogatePairIntoOneChar() {
        val value = (Json.parse(""""\ud83d\ude00"""") as Json.Str).value
        // Two UTF-16 units, one code point: the pair was decoded, not left as eight literal chars.
        assertEquals(2, value.length)
        assertEquals(0x1F600, value.codePointAt(0))
    }

    @Test
    fun keepsALoneSurrogateAsItWasWritten() {
        val high = (Json.parse(""""\ud83d"""") as Json.Str).value
        assertEquals(1, high.length)
        assertEquals(0xD83D, high[0].code)

        val low = (Json.parse(""""\udc00x"""") as Json.Str).value
        assertEquals(2, low.length)
        assertEquals(0xDC00, low[0].code)
        assertEquals('x', low[1])

        // A high surrogate not followed by another \u is not an error; the next escape reads itself.
        val pair = (Json.parse(""""\ud83dA"""") as Json.Str).value
        assertEquals(2, pair.length)
        assertEquals(0xD83D, pair[0].code)
        assertEquals('A', pair[1])
    }

    @Test
    fun reEmitsALoneSurrogateAsAnEscape() {
        val once = Json.parse(""""\ud83d"""")
        assertEquals("\"\\ud83d\"", once.toCompactString())
        assertEquals(once, Json.parse(once.toCompactString()))
    }

    @Test
    fun refusesABadEscape() {
        assertTrue(refusal(""""a\qb"""").message!!.contains("not a JSON escape"))
        assertTrue(refusal("\"a\\").message!!.contains("ends in the middle of an escape"))
        assertTrue(refusal(""""a\u12"""").message!!.contains("four hex digits"))
        assertTrue(refusal(""""a\uZZZZ"""").message!!.contains("not a hex digit"))
    }

    @Test
    fun refusesAnUnterminatedString() {
        assertTrue(refusal("\"abc").message!!.contains("unterminated string"))
        assertTrue(refusal("\"abc\ndef\"").message!!.contains("control character"))
    }

    // ---- numbers -----------------------------------------------------------------------

    @Test
    fun readsExponentAndSign() {
        assertEquals(1000.0, (Json.parse("1e3") as Json.Num).value, 0.0)
        assertEquals(-0.5, (Json.parse("-0.5") as Json.Num).value, 0.0)
        assertEquals(1500.0, (Json.parse("1.5e3") as Json.Num).value, 0.0)
        assertEquals(0.0015, (Json.parse("1.5E-3") as Json.Num).value, 0.0)
        assertEquals(0.0, Math.abs((Json.parse("-0") as Json.Num).value), 0.0)
    }

    @Test
    fun aTwentyDigitIntegerKeepsItsExactText() {
        val num = Json.parse("12345678901234567890") as Json.Num
        assertEquals("12345678901234567890", num.raw)
        // The Double is the rounded value and prints as such; raw is the number the server sent.
        assertEquals(1.2345678901234567E19, num.value, 0.0)
        assertEquals("12345678901234567890", Json.parse(num.toCompactString()).toCompactString())
    }

    @Test
    fun aNineteenDigitIntegerIsStillRoundedInTheDouble() {
        val num = Json.parse("9223372036854775807") as Json.Num
        assertEquals("9223372036854775807", num.raw)
        assertEquals(9.223372036854776E18, num.value, 0.0)
    }

    @Test
    fun refusesWhatIsNotANumber() {
        assertTrue(refusal("nan").message!!.contains("expected 'null'"))
        assertTrue(refusal("NaN").message!!.contains("not the start of a JSON value"))
        assertTrue(refusal("Infinity").message!!.contains("not the start of a JSON value"))
        assertTrue(refusal("-Infinity").message!!.contains("not a digit"))
        assertTrue(refusal("+1").message!!.contains("not the start of a JSON value"))
        assertTrue(refusal(".5").message!!.contains("not the start of a JSON value"))
        assertTrue(refusal("01").message!!.contains("leading zero"))
        assertTrue(refusal("1.").message!!.contains("decimal point needs a digit"))
        assertTrue(refusal("1e").message!!.contains("exponent needs a digit"))
        assertTrue(refusal("1e+").message!!.contains("exponent needs a digit"))
        assertTrue(refusal("0x10").message!!.contains("trailing data"))
    }

    // ---- structure ---------------------------------------------------------------------

    @Test
    fun refusesATrailingComma() {
        assertTrue(refusal("""{"a":1,}""").message!!.contains("trailing comma"))
        assertTrue(refusal("[1,2,]").message!!.contains("trailing comma"))
    }

    @Test
    fun refusesASecondTopLevelValue() {
        assertTrue(refusal("{} {}").message!!.contains("trailing data"))
        assertTrue(refusal("1 2").message!!.contains("trailing data"))
        assertTrue(refusal("null null").message!!.contains("trailing data"))
        assertTrue(refusal("[]x").message!!.contains("trailing data"))
    }

    @Test
    fun refusesAnEmptyOrTruncatedDocument() {
        assertTrue(refusal("").message!!.contains("empty document"))
        assertTrue(refusal("   ").message!!.contains("only whitespace"))
        assertTrue(refusal("{").message!!.contains("unterminated object"))
        assertTrue(refusal("[").message!!.contains("unterminated array"))
        assertTrue(refusal("""{"a":""").message!!.contains("document ends where a value should be"))
        assertTrue(refusal("""{"a" 1}""").message!!.contains("followed by ':'"))
        assertTrue(refusal("{a:1}").message!!.contains("field name has to be a string"))
        assertTrue(refusal("[1 2]").message!!.contains("separated by ','"))
        assertTrue(refusal("tru").message!!.contains("expected 'true'"))
    }

    @Test
    fun aDuplicateKeyKeepsTheLastValue() {
        val obj = Json.parse("""{"a":1,"a":2}""") as Json.Obj
        assertEquals(1, obj.fields.size)
        assertEquals(Json.Num(2.0, "2"), obj.fields["a"])
    }

    @Test
    fun aThousandDeepNestIsRefusedRatherThanTheStack() {
        val e = refusal("[".repeat(1000) + "]".repeat(1000))
        assertTrue(e.message!!.contains("nested deeper than 64 levels"))
        assertEquals(65, e.offset)
    }

    @Test
    fun aNestAtTheLimitStillParses() {
        val deep = "[".repeat(64) + "]".repeat(64)
        assertEquals(deep, Json.parse(deep).toCompactString())
    }

    @Test
    fun theOffsetIsACharacterIndexForAscii() {
        assertEquals(5, refusal("""{"a":tru}""").offset)
        assertEquals(5, refusal("""{"a" 1}""").offset)
    }

    @Test
    fun theOffsetCountsUtf8BytesNotCharacters() {
        // The "é" is two bytes, so the refusal just after it sits at byte 6, character 5.
        assertEquals(6, refusal("[\"é\" x]").offset)
        assertEquals(5, "[\"é\" x]".indexOf('x'))
    }

    // ---- accessors ---------------------------------------------------------------------

    @Test
    fun accessorsReadTheShapeTheyName() {
        val json = Json.parse(
            """{"s":"text","n":7,"f":1.5,"big":12345678901234567890,"b":false,"a":[1,2],"z":null}""",
        )
        assertEquals("text", json.str("s"))
        assertEquals(listOf(Json.Num(1.0, "1"), Json.Num(2.0, "2")), json.arr("a"))
        assertEquals(7L, json.long("n"))
        assertEquals(false, json.bool("b"))
        assertNull(json.str("missing"))
        assertNull(json.arr("s"))
        assertNull(json.str("n"))
        assertNull(json.bool("s"))
        assertNull(json.long("f"))
        assertNull(json.str("z"))
        assertNull(json.long("big"))
    }

    @Test
    fun longReadsThroughRawNotThroughTheDouble() {
        val json = Json.parse("""{"a":9007199254740993,"d":-2}""")
        assertEquals(9007199254740993L, json.long("a"))
        assertEquals(-2L, json.long("d"))
        // 19 digits, and the largest there is.
        assertEquals(Long.MAX_VALUE, Json.parse("""{"n":9223372036854775807}""").long("n"))
        assertEquals(1234567890123456789L, Json.parse("""{"n":1234567890123456789}""").long("n"))
    }

    @Test
    fun aNumberSpelledWithAPointOrAnExponentIsNotAnInteger() {
        // 2^63 written in exponent form is a Double that every range check written in double
        // arithmetic believes in, and converting it answers Long.MIN_VALUE — a plausible wrong
        // number rather than no number. A literal is an integer or it is not, and this asks the
        // spelling: `1e3` and `1.0` are numbers this project will not pretend to know exactly.
        val json = Json.parse("""{"a":9.223372036854775807e18,"b":-1e3,"c":1.0,"d":1E2}""")
        assertNull(json.long("a"))
        assertNull(json.long("b"))
        assertNull(json.long("c"))
        assertNull(json.long("d"))
        assertNull(Json.parse("""{"n":9223372036854775808}""").long("n"))
    }

    @Test
    fun aByteOrderMarkIsNamedRatherThanQuoted() {
        // A message that quotes it looks like it is complaining about nothing: the character is on
        // the screen and not in the text, and hunting for it is the reader's whole afternoon.
        assertTrue(refusal("\uFEFF{}").message!!.contains("byte-order mark"))
    }

    @Test
    fun aWhitespaceOnlyDocumentIsNotCalledEmpty() {
        assertTrue(refusal("  \n\t ").message!!.contains("only whitespace"))
        assertTrue(refusal("").message!!.contains("empty document"))
    }

    @Test
    fun anAccessorOnANonObjectIsNull() {
        assertNull(Json.parse("[1,2]").str("a"))
        assertNull(Json.Null.arr("a"))
    }

    // ---- re-emission -------------------------------------------------------------------

    @Test
    fun reEmitsCompactlyAndReparses() {
        val text = """ { "a" : [ 1 , { "b" : "x" } ] , "c" : null , "d" : true } """
        val once = Json.parse(text).toCompactString()
        assertEquals("""{"a":[1,{"b":"x"}],"c":null,"d":true}""", once)
        assertEquals(once, Json.parse(once).toCompactString())
    }

    @Test
    fun reEmitsEscapesAndNumbersExactly() {
        val text = """{"a":"q\"\\\n\b","b":1e3,"c":-0.5,"d":12345678901234567890}"""
        assertEquals(text, Json.parse(text).toCompactString())
    }

    @Test
    fun aHandBuiltRawThatIsNotANumberIsNotEmitted() {
        // These are the shapes a constructor argument can be given and the parser refuses, so
        // emitting one would make toCompactString's promise false for a value built in this process.
        for (raw in listOf("nan", "01", "1.", ".5", "+1", "", "0x10", "-", "1e")) {
            assertEquals(raw, Json.Num(1.0, "1.0"), Json.parse(Json.Num(1.0, raw).toCompactString()))
        }
    }

    @Test
    fun aHandBuiltNumberWithNoJsonSpellingIsEmittedAsNull() {
        // NaN and the infinities cannot be written in the language at all, and the alternative —
        // throwing out of a toString — would crash whatever happened to be printing the value.
        assertEquals(Json.Null, Json.parse(Json.Num(Double.NaN, "nan").toCompactString()))
        assertEquals(Json.Null, Json.parse(Json.Num(Double.NEGATIVE_INFINITY, "-inf").toCompactString()))
    }

    @Test
    fun aValidRawIsEmittedVerbatim() {
        // The reason raw is kept at all: a 19-digit id must not come back out rounded.
        val num = Json.Num(9223372036854775807.0, "9223372036854775807")
        assertEquals("9223372036854775807", num.toCompactString())
        assertEquals(num, Json.parse(num.toCompactString()))
    }

    @Test
    fun reEmitsNonAsciiAsItself() {
        val text = "\"héllo ☃\""
        assertEquals(text, Json.parse(text).toCompactString())
    }
}
