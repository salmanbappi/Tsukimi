#!/usr/bin/env python3
import subprocess
import json
import time
import sys
import os
import re

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

def get_latest_run(branch):
    cmd = f'gh run list --branch {branch} --limit 5 --json databaseId,status,conclusion,displayTitle,createdAt'
    output, code = run_command(cmd)
    if code == 0 and output:
        runs = json.loads(output)
        if runs:
            # Sort by ID descending to get the absolute newest
            runs.sort(key=lambda x: x['databaseId'], reverse=True)
            return runs[0]
    return None

def diagnose_failure(run_id):
    print(f"\n❌ Build Failed. Fetching diagnostics for Run ID: {run_id}...")
    
    # Get failed logs directly - this is usually the most efficient
    # We use --log-failed to get only the logs of failed steps
    log_cmd = f"gh run view {run_id} --log-failed"
    logs, code = run_command(log_cmd, check=False)
    
    if not logs or "no failed steps" in logs:
        # Fallback to job-based logs if log-failed doesn't return what we want
        print("Falling back to full job logs...")
        cmd = f'gh run view {run_id} --json jobs'
        output, _ = run_command(cmd)
        if output:
            data = json.loads(output)
            failed_job = next((j for j in data.get('jobs', []) if j.get('conclusion') == 'failure'), None)
            if failed_job:
                job_id = failed_job.get('databaseId')
                logs, _ = run_command(f"gh run view {run_id} --job {job_id} --log", check=False)

    if logs:
        # Define better error patterns (regex preferred)
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
        
        # Skip setup noise
        noise_patterns = ["Set up job", "Prepare all required actions", "Getting action download info", "Complete job name"]
        
        for i, line in enumerate(log_lines):
            # Clean up the line (remove timestamp and job prefix if present)
            # Format is usually: "Job Name\tStep Name\tTimestamp Z Line content"
            parts = line.split('\t')
            content = parts[-1] if len(parts) > 1 else line
            
            # Remove timestamp if it's there
            content = re.sub(r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.\d+Z\s+', '', content)
            
            if any(noise in line for noise in noise_patterns):
                continue
                
            if any(re.search(pattern, content) for pattern in error_regexes):
                # Capture some context: 2 lines before, 8 lines after
                start = max(0, i - 2)
                end = min(len(log_lines), i + 10)
                snippet = "\n".join(log_lines[start:end])
                if snippet not in found_errors:
                    found_errors.append(snippet)
        
        if found_errors:
            print("\n--- Identified Error Snippets ---")
            for err in found_errors[:5]: # Show top 5 unique errors
                print(err)
                print("-" * 40)
        else:
            print("\n--- Last 100 lines of failed logs ---")
            print("\n".join(log_lines[-100:]))
    else:
        print("Could not retrieve logs.")

def main():
    branch = get_current_branch()
    print(f"📡 Monitoring branch: {branch}")
    
    # Get the latest run ID
    run = get_latest_run(branch)
    
    if not run:
        print("❌ No runs found.")
        return

    run_id = run['databaseId']
    print(f"🚀 Found Run: {run['displayTitle']} ({run_id})")
    
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
                    data = json.loads(output)
                    status = data['status']
                    conclusion = data['conclusion']
                    
                    if status == 'completed':
                        # Clear line
                        sys.stdout.write('\r' + ' ' * last_msg_len + '\r')
                        break
                    
                    msg = f"Status: {status}... {time.strftime('%H:%M:%S')}"
                    last_msg_len = len(msg)
                    sys.stdout.write(f"\r{msg}")
                    sys.stdout.flush()
                time.sleep(10)
        except KeyboardInterrupt:
            print("\n👋 Stopped watching. Checking final status...")
    
    # Refresh conclusion
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
