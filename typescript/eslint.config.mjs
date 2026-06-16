// The idiom-pass linter (Task 3.1). typescript-eslint type-checked rules over
// src/ + harness/ + tests/, plus the small set of Nest/Vitest accommodations the
// codebase legitimately needs. Honest labeling: this is the project's own idiom
// pass, not a borrowed config — every disabled rule below has a one-line reason.

import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      'eslint.config.stub.mjs',
      'eslint.config.mjs',
      'vitest.config.ts',
      'tests/.*.mjs',
    ],
  },
  ...tseslint.configs.recommendedTypeChecked,
  ...tseslint.configs.stylisticTypeChecked,
  {
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // Empty arrow bodies in the lying-test mocks are deliberate exhibits.
      '@typescript-eslint/no-empty-function': 'off',
      // A leading underscore marks an intentionally-unused binding (interface
      // conformance on a seam method that this impl does not consult — the very
      // shape of several naive variants).
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    },
  },
  {
    // Test + harness code reaches into framework internals (Multer error shapes,
    // dynamic imports, non-null assertions on values the test just seeded), where
    // the strict type-checked rules add noise without catching real defects.
    files: ['tests/**/*.ts', 'harness/**/*.ts'],
    rules: {
      '@typescript-eslint/no-non-null-assertion': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-unsafe-member-access': 'off',
      '@typescript-eslint/no-unsafe-argument': 'off',
      '@typescript-eslint/no-unsafe-call': 'off',
      '@typescript-eslint/no-unsafe-return': 'off',
      // The lying-test mocks and harness fakes are intentionally trivial async
      // shapes (an agent's reflex `async` with no await) — that is the exhibit.
      '@typescript-eslint/require-await': 'off',
    },
  },
);
