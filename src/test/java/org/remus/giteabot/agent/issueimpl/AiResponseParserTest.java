package org.remus.giteabot.agent.issueimpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.model.ImplementationPlan;

import static org.assertj.core.api.Assertions.assertThat;

class AiResponseParserTest {
    private AiResponseParser parser;
    @BeforeEach
    void setUp() {
        parser = new AiResponseParser();
    }
    @Test
    void parseAiResponse_withWriteFileAndValidation_returnsPlanWithTools() {
        String aiResponse = """
                Here is the implementation:
                ```json
                {
                  "summary": "Added hello world feature",
                  "runTools": [
                    {"id": "a1b2c3d4-e5f6-7890-ab12-cdef01234567", "tool": "write-file", "args": ["src/main/java/Hello.java", "public class Hello {}"]},
                    {"id": "b2c3d4e5-f6a7-8901-bc23-def012345678", "tool": "mvn", "args": ["compile", "-q", "-B"]}
                  ]
                }
                ```
                """;
        ImplementationPlan plan = parser.parseAiResponse(aiResponse);
        assertThat(plan).isNotNull();
        assertThat(plan.getSummary()).isEqualTo("Added hello world feature");
        assertThat(plan.hasToolRequest()).isTrue();
        assertThat(plan.getEffectiveToolRequests()).hasSize(2);
        assertThat(plan.getEffectiveToolRequests().get(0).getTool()).isEqualTo("write-file");
        assertThat(plan.getEffectiveToolRequests().get(0).getId()).isEqualTo("a1b2c3d4-e5f6-7890-ab12-cdef01234567");
        assertThat(plan.getEffectiveToolRequests().get(1).getTool()).isEqualTo("mvn");
    }
    @Test
    void parseAiResponse_withPatchFileAndDeleteFile_returnsPlanWithTools() {
        String aiResponse = """
                ```json
                {
                  "summary": "Patched feature X",
                  "runTools": [
                    {"id": "uuid-1111", "tool": "patch-file", "args": ["src/Foo.java", "old text", "new text"]},
                    {"id": "uuid-2222", "tool": "delete-file", "args": ["src/Old.java"]},
                    {"id": "uuid-3333", "tool": "mvn", "args": ["test", "-q", "-B"]}
                  ]
                }
                ```
                """;
        ImplementationPlan plan = parser.parseAiResponse(aiResponse);
        assertThat(plan).isNotNull();
        assertThat(plan.getEffectiveToolRequests()).hasSize(3);
        assertThat(plan.getEffectiveToolRequests().get(0).getTool()).isEqualTo("patch-file");
        assertThat(plan.getEffectiveToolRequests().get(1).getTool()).isEqualTo("delete-file");
        assertThat(plan.getEffectiveToolRequests().get(2).getTool()).isEqualTo("mvn");
    }
    @Test
    void parseAiResponse_invalidJson_returnsNull() {
        assertThat(parser.parseAiResponse("This is not valid JSON at all")).isNull();
    }
    @Test
    void parseAiResponse_emptyResponse_returnsNull() {
        assertThat(parser.parseAiResponse(null)).isNull();
        assertThat(parser.parseAiResponse("")).isNull();
        assertThat(parser.parseAiResponse("   ")).isNull();
    }
    @Test
    void parseAiResponse_noRunTools_returnsPlanWithNoToolRequest() {
        String aiResponse = """
                ```json
                {"summary": "Nothing to do"}
                ```
                """;
        ImplementationPlan plan = parser.parseAiResponse(aiResponse);
        assertThat(plan).isNotNull();
        assertThat(plan.getSummary()).isEqualTo("Nothing to do");
        assertThat(plan.hasToolRequest()).isFalse();
    }
    @Test
    void parseAiResponse_withRequestFiles_returnsPlan() {
        String aiResponse = """
                ```json
                {
                  "summary": "Need more context",
                  "requestFiles": ["src/Main.java", "pom.xml"]
                }
                ```
                """;
        ImplementationPlan plan = parser.parseAiResponse(aiResponse);
        assertThat(plan).isNotNull();
        assertThat(plan.hasFileRequests()).isTrue();
        assertThat(plan.getRequestFiles()).containsExactly("src/Main.java", "pom.xml");
        assertThat(plan.hasToolRequest()).isFalse();
    }
    @Test
    void parseAiResponse_withRequestedFilesAndTools_returnsPlan() {
        String aiResponse = """
                ```json
                {
                  "summary": "Need more context",
                  "requestedFiles": ["src/Main.java"],
                  "requestedTools": [
                    {"tool": "rg", "args": ["UserService.save", "src"]},
                    {"tool": "cat", "args": ["pom.xml", "1", "20"]}
                  ]
                }
                ```
                """;
        ImplementationPlan plan = parser.parseAiResponse(aiResponse);
        assertThat(plan).isNotNull();
        assertThat(plan.hasContextRequests()).isTrue();
        assertThat(plan.getRequestFiles()).containsExactly("src/Main.java");
        assertThat(plan.getRequestTools()).hasSize(2);
        assertThat(plan.getRequestTools().getFirst().getTool()).isEqualTo("rg");
        assertThat(plan.getRequestTools().get(1).getArgs()).containsExactly("pom.xml", "1", "20");
    }
    @Test
    void parseAiResponse_withRunToolLegacy_returnsPlanWithTool() {
        String aiResponse = """
                ```json
                {
                  "summary": "Implemented feature and requesting validation",
                  "runTool": {"tool": "mvn", "args": ["compile", "-q", "-B"]}
                }
                ```
                """;
        ImplementationPlan plan = parser.parseAiResponse(aiResponse);
        assertThat(plan).isNotNull();
        assertThat(plan.getSummary()).isEqualTo("Implemented feature and requesting validation");
        assertThat(plan.hasToolRequest()).isTrue();
        assertThat(plan.getToolRequest()).isNotNull();
        assertThat(plan.getToolRequest().getTool()).isEqualTo("mvn");
        assertThat(plan.getToolRequest().getArgs()).containsExactly("compile", "-q", "-B");
    }
    @Test
    void parseAiResponse_rawJson_withRunTool() {
        String aiResponse = """
                {
                  "summary": "Implemented feature with validation",
                  "runTool": {"tool": "mvn", "args": ["compile", "-q", "-B"]}
                }
                """;
        ImplementationPlan plan = parser.parseAiResponse(aiResponse);
        assertThat(plan).isNotNull();
        assertThat(plan.hasToolRequest()).as("Plan should have tool request").isTrue();
        assertThat(plan.getToolRequest()).isNotNull();
        assertThat(plan.getToolRequest().getTool()).isEqualTo("mvn");
        assertThat(plan.getToolRequest().getArgs()).containsExactly("compile", "-q", "-B");
    }
    @Test
    void parseAiResponse_rawJson_withRunTool_directObjectMapper() {
        String jsonStr = """
                {
                  "summary": "x",
                  "runTool": {"tool": "mvn", "args": ["compile", "-q", "-B"]}
                }
                """;
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        tools.jackson.databind.JsonNode root = mapper.readTree(jsonStr);
        assertThat(root.has("runTool")).isTrue();
        assertThat(root.get("runTool").has("tool")).isTrue();
        assertThat(root.get("runTool").get("tool").asString()).isEqualTo("mvn");
    }
    @Test
    void parseAiResponse_withInvalidEscapeSequence_stillParses() {
        String aiResponse = """
                ```json
                {
                  "summary": "Fix controller",
                  "runTools": [
                    {
                      "id": "uuid-patch",
                      "tool": "patch-file",
                      "args": ["src/main/java/Controller.java", "model.addAttribute();", "model.addAttribute(x);"]
                    }
                  ]
                }
                ```
                """;
        ImplementationPlan plan = parser.parseAiResponse(aiResponse);
        assertThat(plan).isNotNull();
        assertThat(plan.getSummary()).isEqualTo("Fix controller");
        assertThat(plan.getEffectiveToolRequests()).hasSize(1);
        assertThat(plan.getEffectiveToolRequests().getFirst().getTool()).isEqualTo("patch-file");
    }
    // ---- runTools array tests ----
    @Test
    void parseAiResponse_withRunToolsArray_parsesAllTools() {
        String aiResponse = """
                ```json
                {
                  "summary": "Implemented feature with multiple validations",
                  "runTools": [
                    {"id": "a1b2c3d4-1111-2222-3333-444455556666", "tool": "write-file", "args": ["src/main/java/Hello.java", "public class Hello {}"]},
                    {"id": "b2c3d4e5-2222-3333-4444-555566667777", "tool": "mvn", "args": ["compile", "-q", "-B"]},
                    {"id": "c3d4e5f6-3333-4444-5555-666677778888", "tool": "mvn", "args": ["test", "-q", "-B"]}
                  ]
                }
                ```
                """;
        ImplementationPlan plan = parser.parseAiResponse(aiResponse);
        assertThat(plan).isNotNull();
        assertThat(plan.hasToolRequest()).isTrue();
        assertThat(plan.getEffectiveToolRequests()).hasSize(3);
        assertThat(plan.getEffectiveToolRequests().get(0).getId()).isEqualTo("a1b2c3d4-1111-2222-3333-444455556666");
        assertThat(plan.getEffectiveToolRequests().get(0).getTool()).isEqualTo("write-file");
        assertThat(plan.getEffectiveToolRequests().get(1).getId()).isEqualTo("b2c3d4e5-2222-3333-4444-555566667777");
        assertThat(plan.getEffectiveToolRequests().get(1).getTool()).isEqualTo("mvn");
        assertThat(plan.getEffectiveToolRequests().get(2).getId()).isEqualTo("c3d4e5f6-3333-4444-5555-666677778888");
        assertThat(plan.getToolRequest()).isNotNull();
        assertThat(plan.getToolRequest().getId()).isEqualTo("a1b2c3d4-1111-2222-3333-444455556666");
    }
    @Test
    void parseAiResponse_withRunToolsArrayMissingIds_autoGeneratesIds() {
        String aiResponse = """
                ```json
                {
                  "summary": "Multi-tool without IDs",
                  "runTools": [
                    {"tool": "write-file", "args": ["src/Foo.java", "class Foo {}"]},
                    {"tool": "mvn", "args": ["compile"]}
                  ]
                }
                ```
                """;
        ImplementationPlan plan = parser.parseAiResponse(aiResponse);
        assertThat(plan).isNotNull();
        assertThat(plan.getEffectiveToolRequests()).hasSize(2);
        assertThat(plan.getEffectiveToolRequests().get(0).getId()).isEqualTo("tool-1");
        assertThat(plan.getEffectiveToolRequests().get(1).getId()).isEqualTo("tool-2");
    }
    @Test
    void parseAiResponse_withRunToolBackwardCompat_getsAutoId() {
        String aiResponse = """
                ```json
                {
                  "summary": "Old-style single runTool",
                  "runTool": {"tool": "mvn", "args": ["compile", "-q"]}
                }
                ```
                """;
        ImplementationPlan plan = parser.parseAiResponse(aiResponse);
        assertThat(plan).isNotNull();
        assertThat(plan.getEffectiveToolRequests()).hasSize(1);
        assertThat(plan.getEffectiveToolRequests().getFirst().getId()).isEqualTo("tool-1");
        assertThat(plan.getEffectiveToolRequests().getFirst().getTool()).isEqualTo("mvn");
    }

    @Test
    void parseAiResponse_duplicatedResponse_parsesFirstOccurrence() {
        // Simulates the real bug: AI sends the same JSON twice (without markdown fences)
        String json = """
                {"summary":"Need files","requestFiles":["src/Foo.java"],"requestTools":[],"runTools":[]}""";
        String aiResponse = json + "\n" + json; // duplicated
        ImplementationPlan plan = parser.parseAiResponse(aiResponse);
        assertThat(plan).isNotNull();
        assertThat(plan.getSummary()).isEqualTo("Need files");
        assertThat(plan.getRequestFiles()).containsExactly("src/Foo.java");
    }
    @Test
    void truncateToFirstJsonObject_singleObject_unchanged() {
        String json = "{\"summary\":\"hello\"}";
        assertThat(parser.truncateToFirstJsonObject(json)).isEqualTo(json);
    }
    @Test
    void truncateToFirstJsonObject_doubledObject_returnsFirst() {
        String first  = "{\"summary\":\"first\"}";
        String second = "{\"summary\":\"second\"}";
        String input  = first + "\n" + second;
        assertThat(parser.truncateToFirstJsonObject(input)).isEqualTo(first);
    }
    @Test
    void truncateToFirstJsonObject_braceInStringValue_notCountedAsBrace() {
        // The '}' inside the string must not trigger the truncation
        String json = "{\"code\":\"if (x) { return; }\",\"summary\":\"ok\"}";
        assertThat(parser.truncateToFirstJsonObject(json)).isEqualTo(json);
    }
    @Test
    void truncateToFirstJsonObject_emptyInput_returnsNull() {
        assertThat(parser.truncateToFirstJsonObject(null)).isNull();
        assertThat(parser.truncateToFirstJsonObject("")).isEmpty();
    }



    // ---- extractNonJsonResponse tests ----
    @Test
    void extractNonJsonResponse_pureJson_returnsNull() {
        String pureJson = "{\"summary\":\"Do something\",\"runTools\":[]}";
        assertThat(parser.extractNonJsonResponse(pureJson)).isNull();
    }
    @Test
    void extractNonJsonResponse_pureJsonContainingBacktickJson_returnsNull() {
        // Regression: A pure-JSON response (starts with '{') that contains the literal
        // string "```json" inside a tool argument must NOT be treated as having thinking
        // text before a markdown code-fence. The "starts with '{'" check must take
        // priority over the "```json" search.
        String pureJson = "{\"summary\":\"Add docs\",\"runTools\":[{\"id\":\"abc\",\"tool\":\"patch-file\","
                + "\"args\":[\"README.md\",\"old content ```json fenced block end\"]}]}";
        assertThat(parser.extractNonJsonResponse(pureJson)).isNull();
    }
    @Test
    void extractNonJsonResponse_thinkingBeforeJsonBlock_returnsThinking() {
        String response = "Let me think about this.\n\n```json\n{\"summary\":\"x\"}\n```";
        assertThat(parser.extractNonJsonResponse(response)).isEqualTo("Let me think about this.");
    }
    @Test
    void extractNonJsonResponse_startsWithJsonBlock_returnsNull() {
        String response = "```json\n{\"summary\":\"x\"}\n```";
        assertThat(parser.extractNonJsonResponse(response)).isNull();
    }
    @Test
    void extractNonJsonResponse_plainText_returnsAsIs() {
        String response = "Just some text without JSON.";
        assertThat(parser.extractNonJsonResponse(response)).isEqualTo(response);
    }

}