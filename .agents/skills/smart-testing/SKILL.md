# Smart testing (Impulsa.barrio / uso general)

Skill alineada al registro `~/.agents/skills/agents.md`: pruebas por **comportamiento observable**, no por detalles de implementación.

## Prioridad (pirámide)

1. **Reglas de negocio puras** — funciones sin React (validación, derivación de privacidad): tests directos sobre entradas/salidas y mensajes que ve el usuario.
2. **UI crítica** — formularios, flujos que rompen el producto si fallan: Testing Library con consultas accesibles (`getByRole`, `getByLabelText`).
3. **Integración en el borde** — `fetch`, routers: mock **solo en la frontera** (red), no mocks profundos de internals de React.
4. **E2E** — reservado para fases con flujo completo (pago, etc.).

## Reglas

- **No** acoplar tests a clases BEM, estructura interna de componentes ni orden de hooks.
- **Sí** usar roles, nombres accesibles y regiones (`role="region"` + `aria-labelledby`) para anclar secciones de UI en tests.
- **No** sobre-mockear: si mockás `fetch`, no mockees además cada hook interno.
- **Sí** preferir `@testing-library/user-event` sobre `fireEvent` cuando simule interacción real (teclado, clic, checkbox).
- Los mensajes esperados deben coincidir con copy real (validación compartida cliente/servidor).

## Stack en este repo

- Jest + jsdom + `@testing-library/react` + `@testing-library/jest-dom` + `@testing-library/user-event`.

## Referencia

- [Testing Library — Guiding Principles](https://testing-library.com/docs/guiding-principles)
