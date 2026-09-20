# Document content unavailable feedback

Request: metadata-only file_read results must not claim the document body was read;
future Agents must receive a persistent parser hint table without another Agent loop.

Changes:
- Unsupported binary results retain artifact/path metadata but return success=false,
  contentAvailable=false and document_parser_required. Summary explicitly says a
  parser is required. Original file is retained.
- Both model locales load a PDF/DOCX/XLSX/image/text table with file_read's existing
  tool definition. Parser names are candidates, not claims of installed capability.
- Recovery hint instructs the Agent to check available parsers, report inability to
  the user and avoid repeated unsupported reads. No parser was installed, no
  automatic cloud upload or new retry owner was introduced.

Executable regression:
- AgentFileReadSupportTest: unavailable metadata and bilingual tool catalog hints,
  plus existing text, binary and pagination tests.
- scripts/fixtures/file-read-provider.mjs now asserts success=false and
  document_parser_required for the existing PDF UI journey. Other file kinds still
  require success=true.
- Device entry: node scripts/verify-agent-user-journey.mjs SERIAL
  scripts/fixtures/agent-user-journeys/xiaowan-file-read-regression.en.json OUTPUT
  using the existing fixture provider and sample preparation.

Device acceptance for this change: NOT RUN, 待真机验证. Only shared emulator-5580
is connected; no physical Android device. Must verify unsupported PDF/Office
feedback, original file availability, next successful text turn, and restart/history
before accepting the fix. Parser hint text alone does not guarantee a model follows it.

Local verification completed: AgentFileReadSupportTest 8 tests, 0 failures/errors;
assembleDevelopStandardDebug succeeded (final run 37s). Node syntax check of the
updated fixture provider and scoped git diff --check passed. Build command:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.agent.AgentFileReadSupportTest :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
```
