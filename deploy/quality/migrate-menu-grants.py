#!/usr/bin/env python3
"""Transform exported grants without network access; never widen actions or asset scopes."""
import argparse
import copy
import json
from pathlib import Path

MAPPING = {'quality-system-basic': 'charging-basic', **{
    'quality-system-' + suffix: 'charging-' + suffix
    for suffix in ['OrganizationType', 'User', 'Space', 'Customer', 'Dictionary']}}


def flatten(rows):
    return [item for row in rows for item in [
        {key: copy.deepcopy(value) for key, value in row.items() if key != 'children'},
        *flatten(row.get('children', []))]]


def transform(current, business, definitions, baseline=None):
    shape = {menu['id']: menu for menu in flatten(business)}
    roots = {key: row for key, row in shape.items() if row.get('parentId') == '-1'}
    known = {menu['id']: menu for menu in flatten(definitions)}
    # Recovery from a previous partial migration is explicit and uses its saved grant.
    originals = {MAPPING[row['id']]: row for row in flatten((baseline or {}).get('menus', []))
                 if row['id'] in MAPPING}
    output, seen = [], set()
    for saved in flatten(current.get('menus', [])):
        old = originals.get(saved['id'], saved)
        key = old['id']
        if key in roots and key != 'quality-Overview':
            continue
        if key in MAPPING:
            target = MAPPING[key]
            updated = copy.deepcopy(known[target])
            updated['granted'] = old.get('granted', False)
            source_buttons = {button['id']: button for button in known.get(key, {}).get('buttons', [])}
            old_buttons = {button['id']: button for button in old.get('buttons', [])}
            allowed = set()
            for button in old_buttons.values():
                if not button.get('granted'):
                    continue
                # Cloud grant detail omits permissions: resolve by the original menu definition.
                definition = button.get('permissions') or source_buttons.get(button['id'], {}).get('permissions')
                if definition is None:
                    raise ValueError('Missing original permission definition: ' + key + '/' + button['id'])
                allowed.update((permission['permission'], action)
                               for permission in definition for action in permission['actions'])
            for button in updated.get('buttons', []):
                required = {(permission['permission'], action)
                            for permission in button.get('permissions', []) for action in permission['actions']}
                button['granted'] = bool(old_buttons.get(button['id'], {}).get('granted')) and required.issubset(allowed)
        else:
            updated = copy.deepcopy(old)
            if key in shape:
                for field in ['parentId', 'sortIndex', 'icon', 'i18nMessages']:
                    updated[field] = copy.deepcopy(shape[key][field])
        if updated['id'] not in seen:
            output.append(updated)
            seen.add(updated['id'])
    for key, parent in roots.items():
        children = [menu for menu in output if menu.get('parentId') == key]
        if key != 'quality-Overview' and children:
            output.append({**copy.deepcopy(parent), 'granted': any(menu.get('granted') for menu in children)})
    if 'charging-basic' not in seen and any(menu.get('parentId') == 'charging-basic' and menu.get('granted') for menu in output):
        output.append({**copy.deepcopy(known['charging-basic']), 'granted': True})
    return {**copy.deepcopy(current), 'menus': output}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--current', required=True, help='Exported template/service/role grant JSON')
    parser.add_argument('--definitions', required=True, help='Array of original legacy and public menu definitions, including permissions')
    parser.add_argument('--baseline', help='Original grant, only to recover a partial migration')
    parser.add_argument('--business', default=str(Path(__file__).with_name('menus.json')))
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    read = lambda path: json.loads(Path(path).read_text())
    result = transform(read(args.current), read(args.business), read(args.definitions), read(args.baseline) if args.baseline else None)
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
