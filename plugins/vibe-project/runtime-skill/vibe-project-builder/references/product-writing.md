# Product Writing Contract

Write this compact contract before creating files. Keep it in working context;
do not add a separate README unless the product itself needs documentation.

1. **User outcome** — one sentence naming the person, moment, and useful result.
2. **Core flow** — three to seven steps from chat request to visible result.
3. **Truth source** — identify each field as user-entered, locally derived, or fetched from a named source. For live facts, record source URL, retrieval time, freshness rule, and unavailable behavior.
4. **Tool surface** — define narrow Agent-callable verbs for every action users may request in chat. Include read/list tools as well as create/update actions. Do not hide business behavior behind dashboard taps.
5. **State model** — define loading, empty, success, stale, partial, invalid-input, permission-denied, network-error, and retry behavior where applicable.
6. **Safety and compliance** — minimize personal data, explain consent, avoid protected or deceptive claims, and require confirmation for irreversible or externally visible actions. Automated demos must never place real orders, submit payment, send messages, or cross another high-risk confirmation boundary.
7. **Dashboard role** — state what is easier to understand visually and what remains chat-first. The dashboard must use the same stored facts and tools, not a second mock backend.

## Quality Gate

- Never ship placeholder, random, or silently fabricated production data.
- Seed data must be visibly labeled as sample data and removable in one action.
- Show provenance and freshness for externally fetched facts.
- Keep credentials in host-managed Connectors, never HTML, JavaScript, Skill text, or `toolkit.json`.
- Prefer a useful narrow product over a broad dashboard with dead controls.
- Run executable logic tests before publishing; after installing the candidate, run one real end-to-end scenario and one failure/recovery scenario before claiming completion. Follow workflow-validation.md for evidence and blocked-test reporting.
- For commerce-like products, use sandbox data or stop at search/browse; never use checkout or payment as acceptance testing.
