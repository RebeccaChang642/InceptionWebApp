/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
    "./App.jsx",
    "./main.jsx"
  ],
  theme: {
    extend: {
      colors: {
        cosmic: {
          900: '#121418',
          800: '#1B1E24',
          700: '#272C36',
          600: '#343A46',
          cyan: '#80CBB5',
          gold: '#FFA000',
          rose: '#EF5350'
        }
      }
    },
  },
  plugins: [],
}
