import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import './app.css'

const rootElement = document.getElementById('root')

if (rootElement == null) {
  throw new Error('UI root element is missing.')
}

ReactDOM.createRoot(rootElement).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
)
