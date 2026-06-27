#!/usr/bin/env python3
"""Resolve merge conflicts for TS6_Droid_CN sync from upstream."""

import re
import subprocess
import sys

REPO = r"G:\Cfile\Code\Android\TS6_Droid_CN"

def git_show(ref, path):
    r = subprocess.run(["git", "show", f"{ref}:{path}"], capture_output=True, text=True, cwd=REPO, encoding="utf-8")
    if r.returncode != 0:
        print(f"ERROR: git show {ref}:{path} failed: {r.stderr}", file=sys.stderr)
        sys.exit(1)
    return r.stdout

def read_file(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()

def write_file(path, content):
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)

# =========================================================================
# 1. ConnectionViewModel.kt - merge
# =========================================================================
local_vm = read_file(f"{REPO}/app/src/main/kotlin/dev/tsdroid/viewmodel/ConnectionViewModel.kt")

# Remove import conflict markers
local_vm = local_vm.replace(
    "import kotlinx.coroutines.Dispatchers\n<<<<<<< HEAD\nimport kotlinx.coroutines.CompletableDeferred\n=======\nimport kotlinx.coroutines.CancellationException\n>>>>>>> upstream/main",
    "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.CompletableDeferred"
)

# Fix connect() conflict
conn_start = local_vm.find("<<<<<<< HEAD\n                service.connect(addr, identity, nick")
if conn_start >= 0:
    conn_end = local_vm.find(">>>>>>> upstream/main", conn_start)
    conn_end = local_vm.find("\n", conn_end) + 1 if conn_end >= 0 else len(local_vm)
    
    upstream_section = """                var connectionFailure = service.connect(addr, identity, nick, pw)
                if (connectionFailure?.isTooManyClonesFailure() == true) {
                    Log.w(TAG, "Too many clones for saved identity; retrying with a temporary identity")
                    connectionFailure = service.connect(addr, getCloneBypassIdentity(), nick, pw)
                }"""
    local_vm = local_vm[:conn_start] + upstream_section + "\n" + local_vm[conn_end:]

# Fix loadChannels() conflict
lk_start = local_vm.find("<<<<<<< HEAD\n                    // Resolve SRV record if no port")
if lk_start >= 0:
    lk_end = local_vm.find(">>>>>>> upstream/main", lk_start)
    lk_end = local_vm.find("\n", lk_end) + 1 if lk_end >= 0 else len(local_vm)
    
    srv_code = """                    // Resolve SRV record if no port is specified
                    val resolvedAddr = dev.tsdroid.bridge.DnsSrvResolver.resolve(addr)
                    var lastFailure: Throwable? = null

                    for (attempt in 0 until MAX_NICKNAME_COLLISION_ATTEMPTS) {
                        val candidateNickname = dev.tsdroid.bridge.nicknameWithCollisionSuffix(nick, attempt)
                        var client: Client? = null
                        try {
                            try {
                                identity.setNickname(candidateNickname)
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Log.w(TAG, "Failed to update identity nickname before connect", e)
                            }
                            client = Client(resolvedAddr, identity, candidateNickname, pw, null)
                            client.waitConnected()
                            val users = client.users
                            if (users != null && channels != null) break
                            if (!dev.tsdroid.bridge.hasNicknameCollision(users, client.clientId, candidateNickname)) {
                                break
                            }
                            Log.w(TAG, "Nickname collision on attempt $attempt, retrying")
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            lastFailure = e
                            if (!dev.tsdroid.bridge.isNicknameCollisionFailure(e)) throw e
                            Log.w(TAG, "Nickname collision failure on attempt $attempt", e)
                        } finally {
                            if (client != null) {
                                if (dev.tsdroid.bridge.hasNicknameCollision(
                                        client.users, client.clientId, candidateNickname)) {
                                    dev.tsdroid.bridge.destroyClient(client, "nickname collision in channel list")
                                } else {
                                    dev.tsdroid.bridge.closeClient(client, "channel list attempt")
                                }
                            }
                        }
                    }"""
    local_vm = local_vm[:lk_start] + srv_code + "\n" + local_vm[lk_end:]

write_file(f"{REPO}/app/src/main/kotlin/dev/tsdroid/viewmodel/ConnectionViewModel.kt", local_vm)
print("✅ ConnectionViewModel.kt resolved")

# =========================================================================
# 2. TsClient.kt - merge: take upstream + add DnsSrvResolver
# =========================================================================
upstream_ts = git_show("upstream/main", "app/src/main/kotlin/dev/tsdroid/bridge/TsClient.kt")

# Add DnsSrvResolver import after the last existing import
lines = upstream_ts.split('\n')
last_import_idx = 0
for i, line in enumerate(lines):
    if line.startswith("import dev.tsdroid.bridge."):
        last_import_idx = i
    elif line.startswith("import ") and not line.startswith("import dev.tslib"):
        pass

# Insert DnsSrvResolver import
if any("import dev.tsdroid.bridge.DnsSrvResolver" not in line for line in lines):
    lines.insert(last_import_idx + 1, "import dev.tsdroid.bridge.DnsSrvResolver")
upstream_ts = '\n'.join(lines)

# Inject SRV resolution in connect function
upstream_ts = upstream_ts.replace(
    "Client(address, identity, candidateNickname, password, channel)",
    "Client(DnsSrvResolver.resolve(address), identity, candidateNickname, password, channel)"
)
upstream_ts = upstream_ts.replace(
    "serverAddress = address",
    "serverAddress = DnsSrvResolver.resolve(address)"
)

write_file(f"{REPO}/scratch/upstream_ts_merged.kt", upstream_ts)
print("✅ TsClient.kt merged version saved to scratch/upstream_ts_merged.kt")
print("   Now replacing local TsClient.kt with merged version...")

# Copy to actual file
import shutil
shutil.copy(f"{REPO}/scratch/upstream_ts_merged.kt", f"{REPO}/app/src/main/kotlin/dev/tsdroid/bridge/TsClient.kt")
print("✅ TsClient.kt replaced with merged version")
