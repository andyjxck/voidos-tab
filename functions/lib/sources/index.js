'use strict';
/** Every source, in the order Settings › Hub lists them. The App Store runs first (see aggregate.js). */
const costs = require('./costs');

module.exports = [
  require('./appstore').source,
  require('./admob').source,
  require('./firebase').source,
  require('./crashlytics').source,
  require('./sites').source,
  require('./brevo').source,
  require('./github').source,
  costs.billing,
  costs.elevenlabs,
  costs.apple,
];
