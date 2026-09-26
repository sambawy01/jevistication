#!/bin/sh
# The Loupe keyboard must never be able to send anything off the phone (owner rule, 2026-09-26).
# Run by the LoupeKeyboard target after linking (project.yml): fails the build when the keyboard's
# binary imports any network API, or links a networking framework. Checks the main binary and, in
# Debug builds, the .debug.dylib that holds the code. LoupeKit (Kotlin/Native) asks the linker for
# libresolv (the DNS library the app's online checks use); the linker keeps that load command even
# when nothing is used from it, so libresolv is allowed only with zero symbols imported from it.
#
#   scripts/check-no-network.sh <binary> [<binary> …]
set -eu

pattern='NSURLSession|NSURLConnection|URLSession|URLRequest|NSURLRequest|NSStream|CFStream|CFSocket|CFHTTP|CFNetwork|_nw_|NWConnection|NWPathMonitor|WebSocket|WKWebView|_connect$|_connectx$|_getaddrinfo$|_gethostbyname|_socket$|_sendto$|_sendmsg$|_res_9_|_res_n|_dns_|_DNSService'
frameworks='CFNetwork.framework|Network.framework|WebKit.framework|NetworkExtension.framework'

status=0
checked=0
for bin in "$@"; do
  [ -f "$bin" ] || continue
  checked=$((checked + 1))
  found=$(nm -u "$bin" 2>/dev/null | grep -E "$pattern" || true)
  if [ -n "$found" ]; then
    echo "error: the Loupe keyboard must have no network code, but $(basename "$bin") imports:" >&2
    echo "$found" | sed 's/^/error:   /' >&2
    status=1
  fi
  resolv=$(nm -m -u "$bin" 2>/dev/null | grep -c "from libresolv" || true)
  if [ "$resolv" != "0" ]; then
    echo "error: the Loupe keyboard must not use DNS, but $(basename "$bin") imports $resolv symbol(s) from libresolv" >&2
    status=1
  fi
  libs=$(otool -L "$bin" 2>/dev/null | tail -n +2 | grep -E "$frameworks" || true)
  if [ -n "$libs" ]; then
    echo "error: the Loupe keyboard must not link networking libraries, but $(basename "$bin") links:" >&2
    echo "$libs" | sed 's/^/error:   /' >&2
    status=1
  fi
done
if [ "$checked" -eq 0 ]; then
  echo "error: check-no-network: no binary found among: $*" >&2
  exit 1
fi
[ "$status" -eq 0 ] && echo "check-no-network: $checked binary(ies) import no network API"
exit "$status"
