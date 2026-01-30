#!/usr/bin/env python3
import os
import time
import subprocess
import json

def get_latest_run():
    try:
        result = subprocess.run(
            ['gh', 'run', 'list', '--limit', '1', '--json', 'id,status,conclusion,displayTitle,createdAt'],
            capture_output=True, text=True, check=True
        )
        runs = json.loads(result.stdout)
        return runs[0] if runs else None
    except Exception as e:
        print(f"Error fetching runs: {e}")
        return None

def monitor():
    print("Starting GitHub Build Monitor...")
    last_id = None
    while True:
        run = get_latest_run()
        if not run:
            time.sleep(10)
            continue
        
        run_id = run['id']
        status = run['status']
        conclusion = run['conclusion']
        title = run['displayTitle']
        
        if run_id != last_id:
            print(f"\nNew build detected: {title} (ID: {run_id})")
            last_id = run_id
        
        if status == "completed":
            icon = "✅" if conclusion == "success" else "❌"
            print(f"\r{icon} Build {run_id} completed: {conclusion.upper()}        ")
            if conclusion != "success":
                print("\nFetching error logs...")
                subprocess.run(['gh', 'run', 'view', str(run_id), '--log-failed'])
            break
        else:
            print(f"\r⏳ Build {run_id} is {status}... ({run['createdAt']})", end="")
        
        time.sleep(5)

if __name__ == "__main__":
    monitor()
