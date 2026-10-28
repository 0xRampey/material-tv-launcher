#!/usr/bin/env python3
"""Stateful ADB fixture for installer tests; never connects to a device."""
import json
import os
import shlex
import sys
from pathlib import Path

path = Path(os.environ['ADB_TEST_STATE'])
state = json.loads(path.read_text())
args = sys.argv[1:]
with open(os.environ['ADB_TEST_LOG'], 'a') as log:
    log.write(json.dumps(args) + '\n')
if args[:1] == ['-s']:
    args = args[2:]
error = False
output = ''
if args[0] == 'connect':
    output = 'connected to fixture:5555'
elif args[0] == 'wait-for-device':
    pass
elif args[0] == 'install':
    state['installed'] = True
    output = 'Success'
elif args[0] == 'uninstall':
    state['installed'] = False
    state['files'] = {}
    output = 'Success'
elif args[0] == 'push':
    if state.get('fail_push'):
        error = True
    else:
        state['files'][args[2]] = Path(args[1]).read_text()
elif args[0] == 'shell':
    command = shlex.split(args[1]) if len(args) == 2 else args[1:]
    if command == ['am', 'get-current-user']:
        output = '0'
    elif command == ['dumpsys', 'role']:
        holders = ','.join(state['holders'])
        output = 'user_id=0 roles=[{name=android.app.role.ASSISTANT\n' + ('holders=' + holders + '\n' if holders else '') + '}]'
    elif command == ['dumpsys', 'activity', 'activities']:
        output = 'ResumedActivity com.rampey.materialtv/.HomeActivity'
    elif command[:2] == ['cmd', 'role']:
        package = command[-2]
        if command[2] == 'remove-role-holder':
            state['holders'].remove(package)
        else:
            state['holders'] = [package]
    elif command[0] == 'settings':
        offset = 3 if command[1] == '--user' else 1
        verb, _, field = command[offset:offset + 3]
        if verb == 'get':
            value = state['settings'].get(field)
            output = 'null' if value is None else value
        elif verb == 'put':
            state['settings'][field] = command[offset + 3]
        elif verb == 'delete':
            state['settings'].pop(field, None)
    elif command[0] == 'mv':
        state['files'][command[2]] = state['files'].pop(command[1])
    elif command[0] == 'cat':
        if command[1] not in state['files']:
            error = True
        else:
            output = state['files'][command[1]]
    elif command[:2] == ['sh', '-c']:
        key = state['files'].get('/sdcard/Android/data/com.rampey.materialtv/files/openrouter_key', '')
        error = len(''.join(key.split())) <= 10
    elif command[0] in ('mkdir', 'am', 'pm', 'cmd'):
        pass
    else:
        raise AssertionError('Unsupported fixture command: ' + repr(command))
else:
    raise AssertionError('Unsupported fixture operation: ' + repr(args))
path.write_text(json.dumps(state))
if output:
    print(output)
sys.exit(1 if error else 0)
