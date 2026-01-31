#!/usr/bin/env python3
import sys
import subprocess
import time
import json
import shutil

def get_gh_command():
    """Check if 'gh' is available."""
    if shutil.which("gh"):
        return "gh"
    print("Error: GitHub CLI ('gh') is not installed or not in PATH.")
    sys.exit(1)

def run_command(command):
    """Run a shell command and return its output."""
    try:
        result = subprocess.run(command, shell=True, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        return result.stdout.strip()
    except subprocess.CalledProcessError as e:
        return None

def monitor_build():
    """Monitor the latest workflow run."""
    gh = get_gh_command()
    print("Fetching latest workflow run...")
    
    # Get the latest run ID
    cmd = f"{gh} run list --limit 1 --json databaseId,status,conclusion,displayTitle --jq '.[0]'"
    output = run_command(cmd)
    
    if not output:
        print("No workflow runs found.")
        return

    try:
        run_data = json.loads(output)
    except json.JSONDecodeError:
        print("Failed to parse JSON response.")
        return

    run_id = run_data.get("databaseId")
    title = run_data.get("displayTitle")
    
    print(f"Monitoring Build: {title} (ID: {run_id})")
    print("-" * 40)

    while True:
        # Fetch status
        status_cmd = f"{gh} run view {run_id} --json status,conclusion,jobs --jq '{{status: .status, conclusion: .conclusion, jobs: .jobs}}'"
        status_output = run_command(status_cmd)
        
        if not status_output:
            print("Failed to fetch build status. Retrying...")
            time.sleep(5)
            continue

        try:
            status_data = json.loads(status_output)
        except json.JSONDecodeError:
            continue

        status = status_data.get("status")
        conclusion = status_data.get("conclusion")
        jobs = status_data.get("jobs", [])

        # Print Job Details
        sys.stdout.write("\033[K") # Clear line
        job_status_str = " | ".join([f"{j['name']}: {j['conclusion'] or j['status']}" for j in jobs])
        print(f"\rStatus: {status} | {job_status_str}", end="", flush=True)

        if status == "completed":
            print("\n" + "-" * 40)
            if conclusion == "success":
                print("✅ Build SUCCEEDED!")
            else:
                print("❌ Build FAILED!")
                print("Fetching failure logs...")
                print(run_command(f"{gh} run view {run_id} --log-failed"))
            break
        
        time.sleep(10)

if __name__ == "__main__":
    try:
        monitor_build()
    except KeyboardInterrupt:
        print("\nMonitoring stopped by user.")
