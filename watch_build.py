#!/usr/bin/env python3
import subprocess
import json
import time
import sys
import os
import re
import argparse

def run_command(command, check=True):
    try:
        result = subprocess.run(command, shell=True, capture_output=True, text=True, check=check)
        return result.stdout.strip(), result.returncode
    except subprocess.CalledProcessError as e:
        return (e.stdout or "") + (e.stderr or ""), e.returncode
    except Exception as e:
        return str(e), 1

def get_current_branch():
    branch, _ = run_command("git branch --show-current")
    return branch or "preview"

def get_current_sha():
    sha, _ = run_command("git rev-parse HEAD")
    return sha

def get_latest_run(branch, workflow_filter=None, sha_filter=None):
    cmd = f'gh run list --branch {branch} --limit 20 --json databaseId,name,workflowName,status,conclusion,displayTitle,createdAt,headSha'
    output, code = run_command(cmd)
    if code != 0 or not output:
        return None
    
    try:
        runs = json.loads(output)
    except Exception:
        return None
    
    if not runs:
        return None

    # Filter out known non-build utility workflows if no specific workflow is requested
    ignored_keywords = ["discord", "webhook", "issue", "label", "dependabot", "cleanup"]
    
    filtered_runs = []
    for r in runs:
        wf_name = (r.get("workflowName") or r.get("name") or "").lower()
        
        if workflow_filter:
            if workflow_filter.lower() not in wf_name:
                continue
        else:
            # Ignore utility workflows
            if any(k in wf_name for k in ignored_keywords):
                continue
        
        filtered_runs.append(r)

    # Fallback to all runs if filtering removed everything
    if not filtered_runs:
        filtered_runs = runs

    # If SHA filter provided or head SHA matches, prioritize runs for that SHA
    if sha_filter:
        sha_matches = [r for r in filtered_runs if r.get("headSha", "").startswith(sha_filter)]
        if sha_matches:
            filtered_runs = sha_matches

    # Sort by databaseId descending
    filtered_runs.sort(key=lambda x: x['databaseId'], reverse=True)
    return filtered_runs[0]

def diagnose_failure(run_id):
    print(f"\n❌ Build Failed. Fetching diagnostics for Run ID: {run_id}...")
    
    log_cmd = f"gh run view {run_id} --log-failed"
    logs, code = run_command(log_cmd, check=False)
    
    if not logs or "no failed steps" in logs or code != 0:
        print("Falling back to job logs...")
        cmd = f'gh run view {run_id} --json jobs'
        output, _ = run_command(cmd)
        if output:
            try:
                data = json.loads(output)
                failed_job = next((j for j in data.get('jobs', []) if j.get('conclusion') == 'failure'), None)
                if failed_job:
                    job_id = failed_job.get('databaseId')
                    logs, _ = run_command(f"gh run view {run_id} --job {job_id} --log", check=False)
            except Exception:
                pass

    if logs:
        error_regexes = [
            r"Duplicate class .*",
            r"Could not resolve all files .*",
            r"Could not find .*",
            r"FAILURE: Build failed .*",
            r"^e: .*", # Kotlin compiler error
            r"^error: .*", # General error
            r"Unresolved reference: .*",
            r"> Could not GET .*",
            r"> Could not resolve .*"
        ]
        
        log_lines = logs.splitlines()
        found_errors = []
        noise_patterns = ["Set up job", "Prepare all required actions", "Getting action download info", "Complete job name"]
        
        for i, line in enumerate(log_lines):
            parts = line.split('\t')
            content = parts[-1] if len(parts) > 1 else line
            content = re.sub(r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.\d+Z\s+', '', content)
            
            if any(noise in line for noise in noise_patterns):
                continue
                
            if any(re.search(pattern, content) for pattern in error_regexes):
                start = max(0, i - 2)
                end = min(len(log_lines), i + 10)
                snippet = "\n".join(log_lines[start:end])
                if snippet not in found_errors:
                    found_errors.append(snippet)
        
        if found_errors:
            print("\n--- Identified Error Snippets ---")
            for err in found_errors[:5]:
                print(err)
                print("-" * 40)
        else:
            print("\n--- Last 100 lines of failed logs ---")
            print("\n".join(log_lines[-100:]))
    else:
        print("Could not retrieve logs.")

def main():
    parser = argparse.ArgumentParser(description="Watch GitHub build progress.")
    parser.add_argument("branch", nargs="?", default=None, help="Branch name to monitor")
    parser.add_argument("-w", "--workflow", default=None, help="Filter by workflow name")
    parser.add_argument("--sha", default=None, help="Filter by commit SHA")
    args = parser.parse_args()

    branch = args.branch or get_current_branch()
    current_sha = args.sha or get_current_sha()
    
    print(f"📡 Monitoring branch: {branch} (Commit: {current_sha[:7] if current_sha else 'any'})")
    
    run = get_latest_run(branch, workflow_filter=args.workflow, sha_filter=current_sha)
    
    if not run:
        print("❌ No build runs found matching filters.")
        return

    run_id = run['databaseId']
    wf_name = run.get('workflowName') or run.get('name') or 'Build'
    print(f"🚀 Found Run: {wf_name} - {run['displayTitle']} (ID: {run_id})")
    
    status = run['status']
    conclusion = run.get('conclusion')
    
    print(f"📊 Initial Status: {status} ({conclusion or 'in_progress'})")

    if status != 'completed':
        print("📺 Watching build progress...")
        try:
            last_msg_len = 0
            while True:
                status_cmd = f"gh run view {run_id} --json status,conclusion --jq '{{status: .status, conclusion: .conclusion}}'"
                output, code = run_command(status_cmd)
                if code == 0:
                    try:
                        data = json.loads(output)
                        status = data['status']
                        conclusion = data['conclusion']
                        
                        if status == 'completed':
                            sys.stdout.write('\r' + ' ' * last_msg_len + '\r')
                            break
                        
                        msg = f"Status: {status}... {time.strftime('%H:%M:%S')}"
                        last_msg_len = len(msg)
                        sys.stdout.write(f"\r{msg}")
                        sys.stdout.flush()
                    except Exception:
                        pass
                time.sleep(10)
        except KeyboardInterrupt:
            print("\n👋 Stopped watching. Checking final status...")
    
    final_output, _ = run_command(f'gh run view {run_id} --json conclusion --jq ".conclusion"')
    final_conclusion = final_output.strip()
    
    if final_conclusion == "failure":
        diagnose_failure(run_id)
    elif final_conclusion == "success":
        print(f"\n✅ Build Successful! Run ID: {run_id}")
    elif final_conclusion == "cancelled":
        print(f"\n🛑 Build was cancelled.")
    else:
        print(f"\nℹ️ Build ended with conclusion: {final_conclusion}")

if __name__ == "__main__":
    main()
