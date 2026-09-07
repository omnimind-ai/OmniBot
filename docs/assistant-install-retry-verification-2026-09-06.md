# Assistant installation retry and user-facing status

Confirmed defects:
- The installation result sheet classified every installed-but-failed result as waiting for Dispatch, including protocol and process errors.
- The only call to ensureManagedAcpAdapter is the explicit agent/prepare action, but an old Failed-to-prepare health message prevented that action from rerunning the installer.
- When another installation owned the gate, the explicit prepare path returned and proceeded toward initialization without acquiring installation ownership.

Changes reuse existing owners:
- Remove the stale-failure short circuit from explicit preparation; retain health probes, revision checks, installation gate, timeout and command verification.
- Contention now reports the existing preparation-in-progress error instead of silently proceeding. Healthy installed targets still use their existing fast path.
- Ordinary UI shows assistant installation/startup messages and a concrete next step. It does not render raw ACP capabilities, commands embedded in failures, protocol names or Dispatch terminology in installation result/error cards. Raw native diagnostics remain available to engineering.

Verification: ManagedAcpAdapterPreparationTest 10 passed; agent_runtime_capability_contract.test.mjs 45 passed; Dart analysis no issues. Widget cases cover installed initialization failure, explicit retry, successful check, and plain-language configuration/credentials/protocol/contention messages. This change has not been packaged or installed to the phone in this run.
