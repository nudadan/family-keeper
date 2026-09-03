'use strict';

require('dotenv').config();

const express = require('express');
const cors = require('cors');

const positionsRouter = require('./routes/positions');
const audioRouter = require('./routes/audio');
const pickupRouter = require('./routes/pickup');
const adminRouter = require('./routes/admin');
const { startStaleWatchdog } = require('./staleWatchdog');

const app = express();
const PORT = process.env.PORT || 3000;
const API_KEY = process.env.API_KEY;

if (!API_KEY) {
  console.error('FATAL: API_KEY is not set. Copy .env.example to .env and set it.');
  process.exit(1);
}

app.use(cors());
app.use(express.json({ limit: '64kb' }));

// Simple shared-secret auth. Both Android apps send the key in "X-API-Key".
function requireApiKey(req, res, next) {
  const key = req.get('X-API-Key');
  if (key !== API_KEY) {
    return res.status(401).json({ error: 'invalid or missing API key' });
  }
  next();
}

// Health check (no auth) so you can verify the server is up from a browser.
app.get('/health', (req, res) => {
  res.json({ ok: true, time: Date.now() });
});

app.use('/api/positions', requireApiKey, positionsRouter);
app.use('/api/audio', requireApiKey, audioRouter);
app.use('/api/pickup', requireApiKey, pickupRouter);

// Admin site (its own Basic-auth, not the API key).
app.use('/admin', adminRouter);

// Fallback 404 for unknown routes.
app.use((req, res) => {
  res.status(404).json({ error: 'not found' });
});

// Centralized error handler (e.g. malformed JSON body).
app.use((err, req, res, next) => {
  if (err && err.type === 'entity.parse.failed') {
    return res.status(400).json({ error: 'invalid JSON body' });
  }
  console.error(err);
  res.status(500).json({ error: 'internal server error' });
});

app.listen(PORT, () => {
  console.log(`GTracker backend listening on port ${PORT}`);
  startStaleWatchdog();
});
