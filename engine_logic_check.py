"""
Runs the SAME algorithm as EmbeddedPythonEngine.runFile() in Kotlin, just in plain
CPython instead of through the Chaquopy JNI bridge:

    sys.stdout = sys.stderr = io.StringIO()
    sys.argv   = [filename]
    os.chdir(working_directory)
    runpy.run_path(script_path, None, "__main__")

This proves the *logic* (run_name="__main__" so `if __name__ == "__main__"` works,
stdout+stderr capture, cwd switching, exception -> exit code) is correct, since the
Kotlin file calls the exact same three stdlib functions (runpy, io, sys) through
Chaquopy's PyObject.callAttr — Chaquopy is a thin JNI bridge to real CPython, it does
not change what these calls do.
"""
import io
import os
import runpy
import sys


def run_file(script_path: str, working_directory: str):
    original_stdout, original_stderr = sys.stdout, sys.stderr
    original_cwd = os.getcwd()
    captured = io.StringIO()
    exit_code = 0
    try:
        sys.stdout = captured
        sys.stderr = captured
        sys.argv = [os.path.relpath(script_path, working_directory)]
        os.chdir(working_directory)
        try:
            runpy.run_path(os.path.relpath(script_path, working_directory), None, "__main__")
        except SystemExit as e:
            exit_code = e.code if isinstance(e.code, int) else (0 if e.code is None else 1)
        except BaseException as e:
            captured.write(f"\n{type(e).__name__}: {e}\n")
            exit_code = 1
    finally:
        sys.stdout, sys.stderr = original_stdout, original_stderr
        os.chdir(original_cwd)
    return captured.getvalue().rstrip("\n"), exit_code


if __name__ == "__main__":
    workspace = "/home/claude/work/python-engine-check/workspace"
    os.makedirs(workspace, exist_ok=True)

    # Test 1: a normal main.py with the __main__ guard (this is the exact case the user
    # reported failing before: `python main.py`).
    with open(os.path.join(workspace, "main.py"), "w") as f:
        f.write(
            "import sys\n"
            "if __name__ == '__main__':\n"
            "    print('hello from embedded python')\n"
            "    print('argv0=' + sys.argv[0])\n"
        )
    out, code = run_file(os.path.join(workspace, "main.py"), workspace)
    print("=== Test 1: main.py with __main__ guard ===")
    print("exit_code:", code)
    print("output:", repr(out))
    assert code == 0
    assert "hello from embedded python" in out
    assert "argv0=main.py" in out
    print("PASSED\n")

    # Test 2: simple arithmetic (mirrors the terminal-integration instrumented test).
    with open(os.path.join(workspace, "add.py"), "w") as f:
        f.write("print(2 + 2)\n")
    out, code = run_file(os.path.join(workspace, "add.py"), workspace)
    print("=== Test 2: simple script ===")
    print("exit_code:", code)
    print("output:", repr(out))
    assert code == 0 and out == "4"
    print("PASSED\n")

    # Test 3: uncaught exception -> exit code 1, message captured.
    with open(os.path.join(workspace, "broken.py"), "w") as f:
        f.write("raise ValueError('boom')\n")
    out, code = run_file(os.path.join(workspace, "broken.py"), workspace)
    print("=== Test 3: uncaught exception ===")
    print("exit_code:", code)
    print("output:", repr(out))
    assert code == 1 and "ValueError" in out and "boom" in out
    print("PASSED\n")

    # Test 4: explicit sys.exit(3).
    with open(os.path.join(workspace, "exitcode.py"), "w") as f:
        f.write("import sys\nprint('about to exit')\nsys.exit(3)\n")
    out, code = run_file(os.path.join(workspace, "exitcode.py"), workspace)
    print("=== Test 4: sys.exit(3) ===")
    print("exit_code:", code)
    print("output:", repr(out))
    assert code == 3 and "about to exit" in out
    print("PASSED\n")

    # Test 5: script in a subdirectory (mirrors `python sub/nested.py`).
    os.makedirs(os.path.join(workspace, "sub"), exist_ok=True)
    with open(os.path.join(workspace, "sub", "nested.py"), "w") as f:
        f.write("import sys\nprint('nested argv0=' + sys.argv[0])\n")
    out, code = run_file(os.path.join(workspace, "sub", "nested.py"), workspace)
    print("=== Test 5: script in a subdirectory ===")
    print("exit_code:", code)
    print("output:", repr(out))
    assert code == 0 and out == "nested argv0=sub/nested.py"
    print("PASSED\n")

    print("ALL LOGIC CHECKS PASSED")
