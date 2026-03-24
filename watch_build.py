#!/usr/bin/env python3
import subprocess
import json
import time
import sys
import os

def run_command(command):
    try:
        result = subprocess.run(command, shell=True, capture_output=True, text=True)
        return result.stdout.strip(), result.returncode
    except Exception as e:
        return str(e), 1

def get_current_branch():
    branch, _ = run_command("git branch --show-current")
    return branch or "preview"

def get_latest_run(branch):
    print(f"🔍 Searching for latest run on branch: {branch}...")
    cmd = f'gh run list --branch {branch} --limit 1 --json databaseId,status,conclusion,displayTitle'
    output, code = run_command(cmd)
    if code == 0 and output:
        runs = json.loads(output)
        if runs:
            return runs[0]
    return None

def watch_run(run_id):
    print(f"📺 Starting watch for Run ID: {run_id}")
    # Using gh run watch which is the native way to stream logs/status
    subprocess.run(f"gh run watch {run_id}", shell=True)

def diagnose_failure(run_id):
    print(f"\n❌ Build Failed. Fetching diagnostics for Run ID: {run_id}...")
    
    # Get failed steps
    cmd = f'gh run view {run_id} --json jobs'
    output, code = run_command(cmd)
    
    if code == 0:
        data = json.loads(output)
        for job in data.get('jobs', []):
            for step in job.get('steps', []):
                if step.get('conclusion') == 'failure':
                    print(f"\n📍 Failed Step: {step['name']}")
                    print("-" * 40)
                    # Fetch logs for the failed step
                    log_cmd = f"gh run view {run_id} --log-failed"
                    logs, _ = run_command(log_cmd)
                    print(logs)
                    print("-" * 40)

def main():
    branch = get_current_branch()
    
    # Wait for a new run to appear if we just pushed
    max_retries = 5
    run = None
    for i in range(max_retries):
        run = get_latest_run(branch)
        if run and run['status'] != 'completed':
            break
        print("⏳ Waiting for workflow to trigger...")
        time.sleep(5)
    
    if not run:
        print("❌ No active runs found.")
        return

    run_id = run['databaseId']
    print(f"🚀 Found Run: {run['displayTitle']} ({run_id})")
    
    watch_run(run_id)
    
    # Final check
    final_status, _ = run_command(f"gh run view {run_id} --json conclusion --jq '.conclusion'")
    if final_status == "failure":
        diagnose_failure(run_id)
    elif final_status == "success":
        print(f"\n✅ Build Successful! Run ID: {run_id}")
    else:
        print(f"\nℹ️ Build ended with status: {final_status}")

if __name__ == "__main__":
    main()
