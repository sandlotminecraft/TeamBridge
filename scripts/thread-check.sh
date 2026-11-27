#!/usr/bin/env bash
# thread-check.sh: the static thread rules I-1 to I-4 of admindoc spec
# 2026-11-25-hard-team-bridge, section 5.1, checked over src/main.
#
#   scripts/thread-check.sh [<java source root>]   check; exit 0 clean, 1 on any VIOLATION
#   scripts/thread-check.sh --seeds                 prove it can fail: seed one violation per
#                                                   rule into a copy of src/main and expect
#                                                   each to be caught; exit 0 only if all are
#
# The rules, as stated in the spec:
#   I-1  Parties event handlers are thin: only getters on the event, plus one runTask.
#        No Parties or LuckPerms call, no lock, no wait.
#   I-2  Parties API imports appear only in PartiesTeamSource; every Parties call there sits
#        inside a method whose name ends Async; that method is only ever invoked from
#        runTaskAsynchronously.
#   I-3  No blocking tokens: .join( , CompletableFuture...get( , callSyncMethod,
#        CountDownLatch, Semaphore, synchronized, Thread.sleep
#   I-4  Additive only: LuckPerms only through getUserManager().getUser(uuid) and
#        transientData().add/remove of PermissionNodes keyed sandlot.team.*; forbidden:
#        saveUser, saveGroup, createAndLoadGroup, modifyUser, modifyGroup, setPrimaryGroup,
#        InheritanceNode, .data(
#
# How the script reads them (comments and string contents are ignored, except where noted):
#   I-1  A handler is a method whose parameter is a BukkitParties*Event. Each body statement
#        must be either a local copy `T x = <expr>;` where <expr> holds only getter chains
#        on the event (get*()/is*()), null, ==, !=, ?, : -- or the one line
#        `<s>.runTask(() -> <recv>.<m>(<locals>));` whose arguments are those copies and
#        whose receiver is not the event. Exactly one runTask per handler.
#   I-2  (a) `com.alessiodp.parties` appears only in PartiesTeamSource.java.
#        (b) In PartiesTeamSource.java, every `PartiesAPI` or `Parties.getApi(` outside an
#            import lies inside a method whose name ends Async.
#        (c) Every call of a method whose name ends Async is either on a line where
#            `runTaskAsynchronously(` comes before it, or inside the body of another method
#            whose name ends Async (so the chain starts at runTaskAsynchronously), and never
#            on a line with a main-thread `runTask(`. No method references to *Async.
#            Inside an Async method, each runTask( must be a one-line lambda, so the rule
#            above can see what it runs on main.
#   I-3  The tokens, by regex, on code.
#   I-4  The forbidden tokens on code; getUserManager() only as getUserManager().getUser(;
#        transientData() only as transientData().add( or .remove(; PermissionNode.builder(
#        only with NODE_PREFIX or a "sandlot.team. literal; NODE_PREFIX = "sandlot.team.";
#        no other LuckPerms node type or Node.builder(.
#
# A text check cannot see everything (a Runnable built on one line and scheduled on another,
# for example). CP-1 reads the code as well (spec section 9).
set -u

here=$(cd "$(dirname "$0")" && pwd)

check() {
    local root=$1
    if [ ! -d "$root" ]; then
        echo "thread-check: no such directory: $root" >&2
        return 2
    fi
    local files
    mapfile -t files < <(find "$root" -name '*.java' | LC_ALL=C sort)
    if [ ${#files[@]} -eq 0 ]; then
        echo "thread-check: no .java files under $root" >&2
        return 2
    fi
    awk '
    BEGIN {
        DECL = "^((@[A-Za-z_][A-Za-z0-9_.]*(\\([^)]*\\))?)[[:space:]]+)*((public|protected|private|static|final|abstract|default|synchronized|native)[[:space:]]+)*[A-Za-z_][]A-Za-z0-9_<>,.?[ ]*[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*\\("
        NOTDECL = "^(return|new|if|for|while|switch|catch|else|throw|case|do|try)([^A-Za-z0-9_]|$)"
    }
    function decomment(s,    out, i, j, k) {
        gsub(/"([^"\\]|\\.)*"/, "\"\"", s)
        gsub(/'\''([^'\''\\]|\\.)'\''/, "'\'' '\''", s)
        out = ""
        while (length(s) > 0) {
            if (inc) {
                i = index(s, "*/")
                if (i == 0) return out
                s = substr(s, i + 2); inc = 0; continue
            }
            j = index(s, "//"); k = index(s, "/*")
            if (j > 0 && (k == 0 || j < k)) return out substr(s, 1, j - 1)
            if (k > 0) { out = out substr(s, 1, k - 1) " "; s = substr(s, k + 2); inc = 1; continue }
            return out s
        }
        return out
    }
    function trim(s) { sub(/^[[:space:]]+/, "", s); sub(/[[:space:]]+$/, "", s); return s }
    function v(rule, msg) {
        printf "VIOLATION %s %s:%d: %s | %s\n", rule, FILENAME, FNR, msg, trim(raw)
        bad[rule]++
    }
    function count(s, re,    n) { n = gsub(re, "", s); return n }
    function methodname(s,    t, p) {
        t = s
        gsub(/@[A-Za-z_][A-Za-z0-9_.]*(\([^)]*\))?/, "", t)
        p = substr(t, 1, index(t, "(") - 1)
        p = trim(p)
        sub(/.*[^A-Za-z0-9_]/, "", p)
        return p
    }
    function endhandler() {
        if (handler != "" && runtasks != 1)
            printf "VIOLATION I-1 %s: handler %s has %d runTask calls, not 1\n", hfile, handler, runtasks
        if (handler != "" && runtasks != 1) bad["I-1"]++
        handler = ""
    }
    FNR == 1 {
        if (method != "") endhandler()
        depth = 0; method = ""; pending = ""; inc = 0; handler = ""
        base = FILENAME; sub(/.*\//, "", base)
        if (base == "PartiesTeamSource.java") sawsource = 1
    }
    {
        raw = $0
        c = decomment($0)
        t = trim(c)
        before = depth
        isdecl = 0

        # Method declarations (outermost only: methods of anonymous classes stay inside theirs).
        if (method == "" && t != "" && t ~ DECL && t !~ NOTDECL) {
            isdecl = 1
            name = methodname(t)
            if (t ~ /\{/) {
                method = name; mdepth = before + 1
            } else if (t !~ /;$/) {
                pending = name
            }
            if (method == name || pending == name) {
                if (t ~ /\(BukkitParties[A-Za-z]*Event[[:space:]]+[A-Za-z_][A-Za-z0-9_]*\)/) {
                    ev = t; sub(/.*\(BukkitParties[A-Za-z]*Event[[:space:]]+/, "", ev); sub(/\).*/, "", ev)
                    handler = name; hfile = FILENAME; runtasks = 0; delete locals; handlers++
                }
            }
        } else if (pending != "" && t ~ /^\{/) {
            method = pending; mdepth = before + 1; pending = ""
        }

        if (t != "") {
            # I-3: blocking tokens.
            if (c ~ /\.join\(/) v("I-3", ".join(")
            if (c ~ /CompletableFuture.*\.get\(/) v("I-3", "CompletableFuture get(")
            if (c ~ /callSyncMethod/) v("I-3", "callSyncMethod")
            if (c ~ /CountDownLatch/) v("I-3", "CountDownLatch")
            if (c ~ /Semaphore/) v("I-3", "Semaphore")
            if (c ~ /synchronized/) v("I-3", "synchronized")
            if (c ~ /Thread\.sleep/) v("I-3", "Thread.sleep")

            # I-4: additive only.
            if (c ~ /saveUser|saveGroup|createAndLoadGroup|modifyUser|modifyGroup|setPrimaryGroup|InheritanceNode|\.data\(/)
                v("I-4", "forbidden LuckPerms token")
            if (count(c, "getUserManager\\(\\)") != count(c, "getUserManager\\(\\)\\.getUser\\("))
                v("I-4", "getUserManager() used other than as getUserManager().getUser(")
            if (count(c, "transientData\\(\\)") != count(c, "transientData\\(\\)\\.(add|remove)\\("))
                v("I-4", "transientData() used other than .add( or .remove(")
            if (c ~ /PermissionNode\.builder\(/ && raw !~ /PermissionNode\.builder\([[:space:]]*((Membership\.)?NODE_PREFIX[^A-Za-z0-9_]|"sandlot\.team\.)/)
                v("I-4", "PermissionNode key not under sandlot.team.")
            if (c ~ /NODE_PREFIX[[:space:]]*=/ && raw !~ /NODE_PREFIX[[:space:]]*=[[:space:]]*"sandlot\.team\.";/)
                v("I-4", "NODE_PREFIX is not \"sandlot.team.\"")
            if (c ~ /(Meta|Prefix|Suffix|Weight|DisplayName|RegexPermission|Inheritance)Node/ || c ~ /(^|[^A-Za-z0-9_])Node\.builder\(/)
                v("I-4", "a LuckPerms node type other than PermissionNode")

            # I-2 (a): Parties API only in PartiesTeamSource.
            if (c ~ /com\.alessiodp\.parties/ && base != "PartiesTeamSource.java")
                v("I-2", "Parties API outside PartiesTeamSource")
            # I-2 (b): Parties calls only inside *Async methods.
            if (base == "PartiesTeamSource.java" && t !~ /^import / && c ~ /PartiesAPI|Parties\.getApi\(/ && method !~ /Async$/)
                v("I-2", "Parties API used outside a method whose name ends Async")
            # I-2 (c): *Async methods invoked only from runTaskAsynchronously.
            if (!isdecl && c ~ /[A-Za-z0-9_]Async[[:space:]]*\(/) {
                asynccalls++
                if (c ~ /runTask[[:space:]]*\(/)
                    v("I-2", "Async call on a line that hands work to main (runTask)")
                else if (!(method ~ /Async$/ || c ~ /runTaskAsynchronously[[:space:]]*\(.*[A-Za-z0-9_]Async[[:space:]]*\(/))
                    v("I-2", "Async method invoked other than from runTaskAsynchronously")
            }
            if (c ~ /::[A-Za-z0-9_]*Async([^A-Za-z0-9_]|$)/)
                v("I-2", "method reference to an Async method")
            if (method ~ /Async$/ && c ~ /runTask[[:space:]]*\(/ && count(c, "\\(") != count(c, "\\)"))
                v("I-2", "runTask inside an Async method is not a one-line lambda")

            # I-1: thin Parties handlers.
            if (handler != "" && !isdecl && depth >= mdepth && t != "}" && t != "{") {
                if (t ~ /^(final[[:space:]]+)?[A-Za-z_][A-Za-z0-9_<>.]*[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=[^=].*;$/) {
                    lhs = substr(t, 1, index(t, "=") - 1); lhs = trim(lhs); sub(/.*[[:space:]]/, "", lhs)
                    rhs = substr(t, index(t, "=") + 1); sub(/;$/, "", rhs)
                    e = " " rhs
                    gsub("[^A-Za-z0-9_.]" ev "(\\.(get|is)[A-Za-z0-9_]*\\(\\))+", " ", e)
                    gsub(/null|==|!=|\?|:|[[:space:]]/, "", e)
                    if (e == "") locals[lhs] = 1
                    else v("I-1", "handler " handler ": not only getters on the event")
                } else if (t ~ /^[A-Za-z_][A-Za-z0-9_]*\.runTask\(\(\)[[:space:]]*->[[:space:]]*[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*\([^()]*\)\);$/) {
                    runtasks++
                    inner = t; sub(/^[^>]*>[[:space:]]*/, "", inner)
                    recv = inner; sub(/\..*/, "", recv)
                    args = inner; sub(/^[^(]*\(/, "", args); sub(/\)\);$/, "", args)
                    if (recv == ev) v("I-1", "handler " handler ": the runTask lambda calls the event")
                    n = split(args, a, ",")
                    for (i = 1; i <= n; i++) {
                        arg = trim(a[i])
                        if (arg != "" && !(arg in locals)) v("I-1", "handler " handler ": runTask argument " arg " is not a copied value")
                    }
                } else {
                    v("I-1", "handler " handler ": not an event getter copy or the one runTask")
                }
            }
        }

        depth += count(c, "\\{") - count(c, "\\}")
        if (method != "" && depth < mdepth) {
            if (handler != "") endhandler()
            method = ""
        }
    }
    END {
        if (handler != "") endhandler()
        if (!sawsource) { print "VIOLATION I-2: PartiesTeamSource.java not found"; bad["I-2"]++ }
        if (handlers == 0) { print "VIOLATION I-1: no Parties event handler found to check"; bad["I-1"]++ }
        printf "I-1 %s: %d Parties handlers checked\n", (bad["I-1"] ? "FAIL" : "PASS"), handlers
        printf "I-2 %s: %d Async call sites checked\n", (bad["I-2"] ? "FAIL" : "PASS"), asynccalls
        printf "I-3 %s\n", (bad["I-3"] ? "FAIL" : "PASS")
        printf "I-4 %s\n", (bad["I-4"] ? "FAIL" : "PASS")
        exit (bad["I-1"] + bad["I-2"] + bad["I-3"] + bad["I-4"] > 0) ? 1 : 0
    }
    ' "${files[@]}"
}

# One seed per line: rule|file|anchor (ERE, first match)|line inserted after the anchor
SEEDS='I-1|PartiesTeamSource.java|public void onJoin\(|            event.getParty().setDescription("x");
I-1|PartiesTeamSource.java|runTask\(\(\) -> events\.created\(|            scheduler.runTask(() -> events.renamed(id, name));
I-1|PartiesTeamSource.java|public void onRename\(|            UUID leader = Parties.getApi().getParty(id).getLeader();
I-1|PartiesTeamSource.java|public void onDelete\(|            scheduler.runTask(() -> events.deleted(event.getParty().getId(), null));
I-2|Membership.java|^package |import com.alessiodp.parties.api.Parties;
I-2|PartiesTeamSource.java|public Listener listener\(|        int n = Parties.getApi().getPartiesListByName(1, 0).size();
I-2|Membership.java|public void login\(|        source.teamOfAsync(player);
I-2|Membership.java|void lookupAsync\(|        scheduler.runTask(() -> lookedUp(player, stamp, source.teamOfAsync(player)));
I-2|TeamEvents.java|public void enable\(|        scheduler.runTaskAsynchronously(source::allTeamsAsync);
I-3|Directory.java|public int size\(|        new Thread().join();
I-3|Directory.java|public int size\(|        java.util.concurrent.CompletableFuture.completedFuture(1).get();
I-3|Directory.java|public int size\(|        org.bukkit.Bukkit.getScheduler().callSyncMethod(null, null);
I-3|Directory.java|public int size\(|        new java.util.concurrent.CountDownLatch(1);
I-3|Directory.java|public int size\(|        new java.util.concurrent.Semaphore(1);
I-3|Directory.java|public int size\(|        synchronized (names) { }
I-3|Directory.java|public int size\(|        Thread.sleep(1);
I-4|LuckPermsLinks.java|public boolean add\(|        luckPerms.getUserManager().saveUser(null);
I-4|LuckPermsLinks.java|public boolean add\(|        luckPerms.getGroupManager().saveGroup(null);
I-4|LuckPermsLinks.java|public boolean add\(|        luckPerms.getGroupManager().createAndLoadGroup("team");
I-4|LuckPermsLinks.java|public boolean add\(|        luckPerms.getUserManager().modifyUser(player, u -> { });
I-4|LuckPermsLinks.java|public boolean add\(|        luckPerms.getGroupManager().modifyGroup("team", g -> { });
I-4|LuckPermsLinks.java|public boolean add\(|        user.setPrimaryGroup("team");
I-4|LuckPermsLinks.java|public boolean add\(|        user.transientData().add(InheritanceNode.builder("team").build());
I-4|LuckPermsLinks.java|public boolean add\(|        user.data().add(node(partyId, server));
I-4|LuckPermsLinks.java|public boolean add\(|        luckPerms.getUserManager().loadUser(player);
I-4|LuckPermsLinks.java|public boolean add\(|        user.transientData().clear();
I-4|LuckPermsLinks.java|public boolean add\(|        user.transientData().add(PermissionNode.builder("group.admin").build());'

seeds() {
    local src="$here/../src/main/java" tmp out rc caught=0 missed=0 n=0
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' RETURN
    cp -r "$src" "$tmp/clean"
    out=$(check "$tmp/clean"); rc=$?
    if [ $rc -ne 0 ]; then
        echo "$out"
        echo "SEEDS: the unseeded copy does not pass (exit $rc)"
        return 1
    fi
    echo "unseeded copy: exit 0"
    while IFS='|' read -r rule file anchor line; do
        n=$((n + 1))
        rm -rf "$tmp/s"; cp -r "$src" "$tmp/s"
        f=$(find "$tmp/s" -name "$file")
        awk -v re="$anchor" -v add="$line" '{ print } !done && $0 ~ re { print add; done = 1 } END { exit done ? 0 : 3 }' \
            "$f" > "$f.new" || { echo "SEED $n $rule: anchor not found in $file"; missed=$((missed + 1)); continue; }
        mv "$f.new" "$f"
        out=$(check "$tmp/s"); rc=$?
        if [ $rc -ne 0 ] && printf '%s\n' "$out" | grep -q "^VIOLATION $rule"; then
            echo "SEED $n $rule caught (exit $rc): $(printf '%s\n' "$out" | grep -m1 "^VIOLATION $rule" | cut -c1-160)"
            caught=$((caught + 1))
        else
            echo "SEED $n $rule MISSED (exit $rc): $line"
            missed=$((missed + 1))
        fi
    done <<< "$SEEDS"
    echo "SEEDS: $caught caught, $missed missed, of $n"
    [ $missed -eq 0 ]
}

case "${1:-}" in
    --seeds) seeds ;;
    *) check "${1:-$here/../src/main/java}" ;;
esac
