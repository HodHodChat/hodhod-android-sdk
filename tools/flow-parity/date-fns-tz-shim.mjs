// date-fns-tz@1.x is CommonJS; expose the named export the web helpers import.
import { createRequire } from 'node:module';
const require = createRequire('/Users/nobitex/bolbol/chatwoot/package.json');
const pkg = require('date-fns-tz');
export const { utcToZonedTime } = pkg;
export default pkg;
