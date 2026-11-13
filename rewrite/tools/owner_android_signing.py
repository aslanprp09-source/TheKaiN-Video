#!/usr/bin/env python3
"""Owner-only Android key handoff. All secrets remain on the owner's computer.

Requires Python 3.11+, JDK keytool, 7-Zip CLI and GitHub CLI (only for upload).
This program NEVER runs from GitHub Actions and NEVER prints private values.
"""
from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import sys
import tempfile

REPOSITORY = "aslanprp09-source/TheKaiN-Video"
BRANCH = "rebuild-2026-11-11"
ENVIRONMENT = "release-signing"
REVIEWER_ID = 355790290
RULESET_ID = 27181193
EXPECTED_CHECKS = {"ci-policy", "windows-tests", "android-debug", "android-device-tests"}
KEY_ALIAS = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z")
PEM_CERT = re.compile(rb"-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----", re.S)
SCRIPT_ROOT = Path(__file__).resolve().parents[2]


class SetupError(Exception):
    """A user-actionable failure without secret-bearing stderr."""


def owner_only():
    if os.environ.get("CI", "").lower() in {"true", "1"} or os.environ.get("GITHUB_ACTIONS", "").lower() == "true":
        raise SetupError("This owner-only tool must run on a trusted local computer, not in CI")


def executable(name):
    found = shutil.which(name)
    if found:
        return found
    if name == "keytool":
        home = os.environ.get("JAVA_HOME")
        if home:
            path = Path(home) / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")
            if path.is_file():
                return str(path)
    if name == "7z":
        for alt in ("7zz", "7z.exe"):
            found = shutil.which(alt)
            if found:
                return found
    raise SetupError(f"Required local tool is missing: {name}")


def private_file(path, must_exist):
    raw = Path(path).expanduser()
    if raw.is_symlink():
        raise SetupError("Refusing a symbolic-link keystore path")
    file = raw.resolve()
    if file == SCRIPT_ROOT or SCRIPT_ROOT in file.parents:
        raise SetupError("Private material must stay outside the repository and its working tree")
    if must_exist:
        if not file.is_file() or file.is_symlink() or not file.stat().st_size:
            raise SetupError("Keystore file is missing, empty or unsafe")
    elif file.exists() or file.is_symlink():
        raise SetupError("Output already exists; refusing to overwrite")
    if file.suffix.lower() not in (".p12", ".pfx"):
        raise SetupError("Use a .p12 or .pfx PKCS#12 keystore file")
    if not file.parent.is_dir():
        raise SetupError("Keystore parent directory must already exist and be owner-controlled")
    return file


def alias_ok(alias):
    if not KEY_ALIAS.fullmatch(alias):
        raise SetupError("Invalid alias; use 1-128 ASCII letters, digits, _ . or -")
    return alias


def run_interactive(command, *, cwd=None):
    """Allow a native program to prompt without putting a password in argv."""
    try:
        subprocess.run(command, cwd=cwd, check=True)
    except (OSError, subprocess.CalledProcessError) as exc:
        raise SetupError(f"Local tool failed while running {Path(command[0]).name}; secret values were not logged") from None


def generate(keystore, alias):
    owner_only()
    path = private_file(keystore, must_exist=False)
    alias_ok(alias)
    keytool = executable("keytool")
    print("keytool will ask for the PKCS#12 password interactively. Never share it.")
    command = [keytool, "-genkeypair", "-storetype", "PKCS12", "-keystore", str(path),
               "-alias", alias, "-keyalg", "RSA", "-keysize", "4096",
               "-sigalg", "SHA256withRSA", "-validity", "10000",
               "-dname", "CN=TheKaiN Android Release"]
    run_interactive(command)
    if not path.is_file() or path.stat().st_size == 0:
        raise SetupError("keytool did not produce a nonempty keystore")
    if os.name != "nt":
        path.chmod(0o600)
    print("Keystore created locally. It is NOT backed up or uploaded. Create and verify both backups next.")


def digest(file):
    h = hashlib.sha256()
    with Path(file).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def backup_paths(keystore, one, two, *, new):
    source = private_file(keystore, must_exist=True)
    raw_archives = [Path(p).expanduser() for p in (one, two)]
    if any(p.is_symlink() for p in raw_archives):
        raise SetupError("Backup archive path may not be a symbolic link")
    archives = [p.resolve() for p in raw_archives]
    if archives[0] == archives[1] or source in archives:
        raise SetupError("Backups must have distinct paths, different from the keystore")
    for archive in archives:
        if archive.suffix.lower() != ".7z":
            raise SetupError("Encrypted backups must have the .7z extension")
        if not archive.parent.is_dir() or SCRIPT_ROOT in archive.parents:
            raise SetupError("Backup directories must exist outside the repository")
        if new and (archive.exists() or archive.is_symlink()):
            raise SetupError("Backup already exists; refusing to overwrite")
        if not new and (not archive.is_file() or archive.is_symlink()):
            raise SetupError("Required backup archive does not exist")
    return source, archives


def verify_archives(source, archives, archive_tool):
    original_digest = digest(source)
    for index, archive in enumerate(archives, 1):
        print(f"Restoring encrypted backup {index}/2 into an automatically removed temporary directory ...")
        with tempfile.TemporaryDirectory(prefix="thekain-backup-check-") as tmp:
            # 'e' ignores stored paths: only the expected filename is restored to tmp.
            run_interactive([archive_tool, "e", "-p", "-y", f"-o{tmp}", str(archive), source.name])
            restored = Path(tmp) / source.name
            if not restored.is_file() or restored.is_symlink() or digest(restored) != original_digest:
                raise SetupError(f"Backup {index} failed the independent SHA-256 restore comparison")
    print("PASS: two archives independently restored and their contents match the original keystore")


def backup(keystore, one, two):
    owner_only()
    source, archives = backup_paths(keystore, one, two, new=True)
    seven_zip = executable("7z")
    print("IMPORTANT: store copies on two independent encrypted/offline media. 7-Zip will prompt for each archive passphrase.")
    for i, path in enumerate(archives, 1):
        print(f"Creating encrypted archive {i}/2. Password will not be passed on the command line.")
        run_interactive([seven_zip, "a", "-t7z", "-mhe=on", "-p", str(path), source.name], cwd=source.parent)
        if not path.is_file() or not path.stat().st_size:
            raise SetupError(f"Encrypted archive {i} was not created")
    verify_archives(source, archives, seven_zip)


def fingerprint(keytool, keystore, alias, password):
    """Export public DER certificate only; password enters keytool via child env, never argv."""
    child_env = os.environ.copy()
    child_env["THEKAIN_LOCAL_STOREPASS"] = password
    args = [keytool, "-exportcert", "-rfc", "-storetype", "PKCS12", "-keystore", str(keystore),
            "-alias", alias, "-storepass:env", "THEKAIN_LOCAL_STOREPASS"]
    try:
        result = subprocess.run(args, env=child_env, capture_output=True, check=True, timeout=90)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
        raise SetupError("Could not verify the certificate/alias/password using keytool") from None
    finally:
        child_env.pop("THEKAIN_LOCAL_STOREPASS", None)
    matches = PEM_CERT.findall(result.stdout)
    if len(matches) != 1:
        raise SetupError("Exactly one exported public X.509 certificate was expected")
    try:
        pem = b"-----BEGIN CERTIFICATE-----\n" + matches[0] + b"\n-----END CERTIFICATE-----"
        der = ssl.PEM_cert_to_DER_cert(pem.decode("ascii"))
    except (ValueError, UnicodeError):
        raise SetupError("Exported public certificate is invalid") from None
    return hashlib.sha256(der).hexdigest()



def verify_private_key(keytool, keystore, alias, store_password, key_password):
    """Prove that both passwords unlock the private signing key, without exporting it."""
    child_env = os.environ.copy()
    child_env["THEKAIN_LOCAL_STOREPASS"] = store_password
    child_env["THEKAIN_LOCAL_KEYPASS"] = key_password
    # A CSR contains only the public key and a signature proving access to the private key.
    # The temporary public CSR is removed when this block completes.
    with tempfile.TemporaryDirectory(prefix="thekain-key-access-") as temporary:
        proof = Path(temporary) / "access.csr"
        args = [keytool, "-certreq", "-storetype", "PKCS12", "-keystore", str(keystore),
                "-alias", alias, "-file", str(proof),
                "-storepass:env", "THEKAIN_LOCAL_STOREPASS",
                "-keypass:env", "THEKAIN_LOCAL_KEYPASS"]
        try:
            subprocess.run(args, env=child_env, capture_output=True, check=True, timeout=90)
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            raise SetupError("Cannot access signing private key with supplied passwords/alias") from None
        finally:
            child_env.pop("THEKAIN_LOCAL_STOREPASS", None)
            child_env.pop("THEKAIN_LOCAL_KEYPASS", None)
        if not proof.is_file() or proof.stat().st_size == 0:
            raise SetupError("Private key access check did not produce a public certificate request")


def gh_json(gh, endpoint):
    try:
        result = subprocess.run([gh, "api", endpoint], capture_output=True, check=True, timeout=35, text=True)
        return json.loads(result.stdout)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError):
        raise SetupError("Unable to read/verify GitHub environment and ruleset via your local gh authentication") from None


def check_github(gh):
    environment = gh_json(gh, f"repos/{REPOSITORY}/environments/{ENVIRONMENT}")
    policies = gh_json(gh, f"repos/{REPOSITORY}/environments/{ENVIRONMENT}/deployment-branch-policies?per_page=100")
    rule = gh_json(gh, f"repos/{REPOSITORY}/rulesets/{RULESET_ID}")
    if environment.get("name") != ENVIRONMENT or environment.get("can_admins_bypass") is not False:
        raise SetupError("Protected signing environment is absent or allows admin bypass")
    r = [x for x in environment.get("protection_rules", []) if x.get("type") == "required_reviewers"]
    if len(r) != 1 or r[0].get("prevent_self_review") is not True or \
            [(x.get("type"), x.get("reviewer", {}).get("id")) for x in r[0].get("reviewers", [])] != [("User", REVIEWER_ID)]:
        raise SetupError("Required independent signing reviewer does not match verified configuration")
    branches = policies.get("branch_policies", [])
    if policies.get("total_count") != 1 or len(branches) != 1 or \
            (branches[0].get("name"), branches[0].get("type")) != (BRANCH, "branch") or \
            environment.get("deployment_branch_policy") != {"protected_branches": False, "custom_branch_policies": True}:
        raise SetupError("Deployment branch restrictions are not limited to the rebuild branch")
    if rule.get("id") != RULESET_ID or rule.get("enforcement") != "active" or \
            rule.get("conditions", {}).get("ref_name") != {"include": [f"refs/heads/{BRANCH}"], "exclude": []} or \
            rule.get("bypass_actors") != []:
        raise SetupError("Protected branch ruleset is missing or does not match the expected scope")
    types = {x.get("type"): x for x in rule.get("rules", [])}
    if not {"deletion", "non_fast_forward", "pull_request", "required_status_checks"}.issubset(types):
        raise SetupError("Mandatory branch rule types are missing")
    review = types["pull_request"].get("parameters", {})
    if review.get("required_approving_review_count", 0) < 1 or any(review.get(k) is not True for k in
            ("dismiss_stale_reviews_on_push", "require_last_push_approval", "required_review_thread_resolution")):
        raise SetupError("Branch review requirements are weaker than the approved policy")
    status = types["required_status_checks"].get("parameters", {})
    checks = status.get("required_status_checks", [])
    if status.get("strict_required_status_checks_policy") is not True or \
            {c.get("context") for c in checks} != EXPECTED_CHECKS or \
            any(c.get("integration_id") != 15368 for c in checks):
        raise SetupError("Branch required CI checks differ from the approved policy")
    print("PASS: owner gh API confirms the protected signing environment and branch ruleset")


def gh_set(gh, kind, name, value):
    # Never use --body with secret values. The subprocess receives only stdin, with captured output discarded.
    command = [gh, kind, "set", name, "--env", ENVIRONMENT, "--repo", REPOSITORY]
    try:
        subprocess.run(command, input=value, text=True, capture_output=True, check=True, timeout=60)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
        raise SetupError(f"GitHub rejected {kind} '{name}'; some earlier values may already be set") from None


def publish(keystore, alias, one, two):
    owner_only()
    alias_ok(alias)
    source, archives = backup_paths(keystore, one, two, new=False)
    keytool, seven_zip, gh = executable("keytool"), executable("7z"), executable("gh")
    verify_archives(source, archives, seven_zip)
    store_password = getpass.getpass("Existing keystore password (not displayed): ")
    if not store_password:
        raise SetupError("A nonempty keystore password is required")
    key_password = getpass.getpass("Private key password (often the same for PKCS#12; not displayed): ")
    if not key_password:
        raise SetupError("A nonempty private key password is required")
    cert_sha256 = fingerprint(keytool, source, alias, store_password)
    verify_private_key(keytool, source, alias, store_password, key_password)
    check_github(gh)
    print("PUBLIC certificate SHA-256: " + cert_sha256)
    print("Only environment secrets/variables will be changed. No workflows will be started.")
    if input("Type UPLOAD to add exactly four secrets and one public variable to release-signing: ").strip() != "UPLOAD":
        raise SetupError("Cancelled before changing GitHub")
    values = {
        "ANDROID_KEYSTORE_BASE64": base64.b64encode(source.read_bytes()).decode("ascii"),
        "ANDROID_KEYSTORE_PASSWORD": store_password,
        "ANDROID_KEY_ALIAS": alias,
        "ANDROID_KEY_PASSWORD": key_password,
    }
    for name, value in values.items():
        gh_set(gh, "secret", name, value)
        print(f"Set environment-only secret: {name} (value not shown)")
    gh_set(gh, "variable", "ANDROID_SIGNING_CERT_SHA256", cert_sha256)
    print("PASS: four environment secrets and the PUBLIC certificate fingerprint submitted")
    print("PUBLIC SHA-256: " + cert_sha256)
    print("Next: owner confirms setup; independent KaiN1One approval is still required for a separate signing run.")


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="command", required=True)
    g = sub.add_parser("generate", help="Generate PKCS#12 with keytool interactive password prompts")
    g.add_argument("--keystore", required=True)
    g.add_argument("--alias", required=True)
    b = sub.add_parser("backup", help="Make and restore-test two independently encrypted .7z backups")
    b.add_argument("--keystore", required=True)
    b.add_argument("--backup-one", required=True)
    b.add_argument("--backup-two", required=True)
    v = sub.add_parser("verify", help="Restore-test both existing backups again, without GitHub access")
    v.add_argument("--keystore", required=True)
    v.add_argument("--backup-one", required=True)
    v.add_argument("--backup-two", required=True)
    s = sub.add_parser("publish", help="Verify backups/policy; upload secrets and public fingerprint")
    s.add_argument("--keystore", required=True)
    s.add_argument("--alias", required=True)
    s.add_argument("--backup-one", required=True)
    s.add_argument("--backup-two", required=True)
    opts = p.parse_args(argv)
    try:
        if opts.command == "generate":
            generate(opts.keystore, opts.alias)
        elif opts.command == "backup":
            backup(opts.keystore, opts.backup_one, opts.backup_two)
        elif opts.command == "verify":
            owner_only()
            source, archives = backup_paths(opts.keystore, opts.backup_one, opts.backup_two, new=False)
            verify_archives(source, archives, executable("7z"))
        else:
            publish(opts.keystore, opts.alias, opts.backup_one, opts.backup_two)
        return 0
    except (SetupError, KeyboardInterrupt) as exc:
        print("ERROR: " + (str(exc) if isinstance(exc, SetupError) else "Interrupted"), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())