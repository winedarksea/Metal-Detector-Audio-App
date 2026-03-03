## General Code Guidelines

### Small Files & Single Responsibility
- **Aim to keep files < 500 lines.** Large files are difficult to reason about and cause merge conflicts.
- **Single Responsibility per File:** Each module should have one reason to change.

### Hyper-Descriptive Naming
- **Favor Explicit Over Concise:** Use long, descriptive names that explain intent

### High-Signal Comments
- **Explain "Why", Not "What":** Comments should explain the reasoning behind complex algorithms or architectural decisions.
- **Be Token-Efficient in Comments:** Use concise, informative language. Focus on documenting interface contracts and capability tiers.

### Testing & Benchmarking
- **Shared Fixtures:** Extract common test utilities (synthetic signal generators, stub storage) into a shared helper module if reused across 3+ files.

Note: OpenJDK@21 is needed for builds and conda env `gpu311` for tensorflow
