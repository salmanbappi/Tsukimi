import re
import sys

def bump_version(file_path):
    with open(file_path, 'r') as f:
        content = f.read()

    # Bump versionCode
    def replace_code(match):
        return f"versionCode {int(match.group(1)) + 1}"
    
    content_new = re.sub(r'versionCode\s+(\d+)', replace_code, content)

    # Bump versionName (Patch version)
    def replace_name(match):
        major, minor, patch = map(int, match.group(1).split('.'))
        return f"versionName = '{major}.{minor}.{patch + 1}'"

    content_new = re.sub(r"versionName\s*=?\s*'(\d+\.\d+\.\d+)'", replace_name, content_new)

    if content != content_new:
        with open(file_path, 'w') as f:
            f.write(content_new)
        print("Version bumped successfully.")
        return True
    else:
        print("No version pattern matched or no change needed.")
        return False

if __name__ == "__main__":
    if len(sys.argv) > 1:
        bump_version(sys.argv[1])
    else:
        print("Usage: python bump_version.py <path_to_build.gradle>")
