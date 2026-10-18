# Reference reliability and coherent prompt validation

Scope: [failure trace](../research/2026-10-19-reference-reliability.md), [implementation plan](../plans/2026-10-19-reference-reliability-and-coherent-prompt.md) and [behavior-driven contract](../specs/2026-10-14-behavior-driven-tools.md).

## Baseline and regression

The installed baseline is SHA-256 `C6DD1751F858AB63088246DEEB9B03C06083865ECA0BA8A579416C5EF1F4C299`. The live fixture and settings are unchanged from the [45-turn stress test](2026-10-18-natural-prompt-stress-test.md): GPT-6-Luna Extra high, screenshot on, automatic search off and no developer prompt override. Properties hash is `AF7DA001C214347F01A17D76A991150D1728907B5EC16615FC2EE1126DAA5FA0`. The baseline JAR is retained under ignored `.tools/reference-reliability/before-packsage.jar`.

Commit `d8ced40` adds a pass-through backend seam and two behavior regressions. Java 21 `gradlew test --tests dev.packsage.model.ReferenceAliasBackendTest --tests dev.packsage.model.GroundingInstructionsTest` completed 11 tests in 44 seconds: the two new reference tests failed at their alias assertions, while all nine request-assembly tests passed. The failures reproduce the missing compact-reference behavior, not a compiler problem. The fixtures include observed tool calls before submitting their results so subsequent replay validation exercises a realistic exchange.

## Whole-prompt review

The instruction block was replaced as a whole, following request, evidence, feasibility, investigation and answer decisions. Its final reviewed text is 1,533 whitespace-delimited words versus 1,888 before rewriting. The action boundary is at the start; proof, uncertainty and reference rules have one governing location instead of added scenario warnings.

Independent review identified four concrete issues: ordinary crafting questions could be mistaken for action requests, reported actions needed attribution rather than unqualified present-state claims, shortage conclusions needed complete requirements or a conclusive lower bound, and retrieved history needed citation. These were corrected in the relevant paragraphs. A final review found one repeated reported-action sentence; removing it left no other reported actionable contradiction or material lost contract. These are source-review findings, not proof of improved model behavior.

Reference implementation, complete automated checks and installed acceptance remain pending at this checkpoint. Historical corrupted references are not repaired by guessing a similar source.
