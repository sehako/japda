import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  test: {
    projects: [
      {
        test: {
          name: 'node',
          include: ['tests/**/*.test.ts'],
          environment: 'node',
        },
      },
      {
        test: {
          name: 'dom',
          include: ['tests/**/*.test.tsx'],
          environment: 'jsdom',
          setupFiles: './tests/setup.ts',
        },
      },
    ],
  },
})
