/**
 * Plain JavaScript CommonJS client — syntax only, never executed.
 */
const path = require("path");

function loadLegacyConfig(baseDir) {
  return path.join(baseDir, "legacy.json");
}

function describeLegacy() {
  return "legacy-client";
}

module.exports = {
  loadLegacyConfig,
  describeLegacy,
};
