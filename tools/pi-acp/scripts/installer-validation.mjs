import { gunzipSync } from "node:zlib";

function embeddedArchive(installer) {
  const matches = [...installer.matchAll(/Buffer\.from\('([A-Za-z0-9+/=]+)', 'base64'\)/g)];
  if (matches.length !== 1) return undefined;
  const [match] = matches;
  return {
    compressed: Buffer.from(match[1], "base64"),
    template: installer.slice(0, match.index) + "<PI_ARCHIVE>" + installer.slice(match.index + match[0].length),
  };
}

/** Compare shipped file bytes and installer logic, not the gzip container. */
export function matchesAndroidInstaller(actual, expected, archive) {
  const shipped = embeddedArchive(actual);
  const generated = embeddedArchive(expected);
  if (!shipped || !generated || shipped.template !== generated.template) return false;
  try {
    // zlib writes a platform-dependent OS byte (Darwin 19, Unix 3), and
    // compressor versions can encode identical input differently. Neither
    // changes the files installed on Android. Keep those files byte-exact.
    return gunzipSync(shipped.compressed).equals(Buffer.from(archive));
  } catch {
    return false;
  }
}
