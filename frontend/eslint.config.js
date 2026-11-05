import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import react from 'eslint-plugin-react';
import globals from 'globals';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'test-results'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: { globals: { ...globals.browser } },
    plugins: { react },
    settings: { react: { version: '18.3' } },
    rules: {
      // Message bodies are user input: render them as text, never as HTML (ADR-0010, CSP in ADR-0011).
      'react/no-danger': 'error',
      'react/no-danger-with-children': 'error',
      'no-restricted-properties': ['error',
        { property: 'innerHTML', message: 'Render text, never HTML.' },
        { property: 'outerHTML', message: 'Render text, never HTML.' }],
    },
  },
);
