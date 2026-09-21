# Security policy

## Reporting a vulnerability

Please use GitHub's [private vulnerability reporting](https://github.com/Zain-Imam/just-player-pro/security/advisories/new)
rather than opening a public issue, so that a problem is not published before
there is something to update to.

This is a one-person project with no support desk behind it. Reports are read
and acted on as quickly as is realistic, and nothing is promised beyond that.
There is no bounty.

## What is worth reporting

The player asks for very little: it reads the videos on the device, and it
reaches the network only when asked and only with keys the user supplies. The
places where that could go wrong:

* **Set up from your phone.** While the settings screen is open, the app runs a
  PIN-protected web server on the local network so that keys can be typed on a
  real keyboard instead of with a remote. Anything that gets past the PIN,
  leaves the port open when it should be closed, or exposes a key through it.
* **Keys and addresses leaking.** API keys, addon URLs or history turning up in
  logs, crash reports, or an export that was asked not to include them.
* **A crafted file causing more than a failure.** A media file, subtitle or link
  that does something beyond refusing to play.
* **Anything reaching the network that should not**, or reaching it when the
  online features are switched off.

Bugs that are not security problems belong in the
[issue tracker](https://github.com/Zain-Imam/just-player-pro/issues) — that is
the faster route for them.

## Supported versions

The most recent release only. There are no backports to older versions.

## Verifying a build came from here

Every release is signed with one key, and its fingerprint is published in the
[README](README.md#verifying-a-build-came-from-here). A build reporting a
different certificate did not come from this project, whatever it is called and
wherever it was downloaded from:

```
apksigner verify --print-certs just-player-pro-4.0.0-arm64-v8a.apk
```

Releases are published only on the
[releases page](https://github.com/Zain-Imam/just-player-pro/releases) of this
repository.
