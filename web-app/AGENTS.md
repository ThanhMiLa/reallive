# Web App Conventions

Follow the repository root guidelines. These conventions apply to all UI work, including small changes that do not need the frontend-design skill.

## UI Behavior

- Reuse existing components, design tokens, typography, and interaction patterns when extending a screen.
- Keep affected layouts usable on mobile and desktop without unintended horizontal overflow.
- Preserve keyboard operation and visible focus. Use semantic controls, accessible labels, and readable text contrast.
- Respect reduced-motion preferences when adding or changing animations.
- Keep relevant loading, empty, error, and success states understandable, with consistent product terminology.

## Verification

- Use `npm run build` and `npm run lint` for implementation changes. Run relevant tests: `npm test` covers the Node test suite; `npm run test:streaming` runs the streaming Vitest suite.
- Inspect affected screens at relevant viewport sizes when a browser is available. Include screenshots for UI changes in PRs, as required by the root guidelines.
