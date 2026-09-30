#!/usr/bin/env python3
"""Regression: localize object constructors, never array component types."""
from __future__ import annotations
import re
from apply_unified import ui_boundaries


def run_checks(check):
    widgets = ('TextView', 'Button', 'CheckBox', 'EditText', 'ArrayAdapter')
    for name in widgets:
        replacement = 'Localized' + name
        for prefix in ('', 'android.widget.'):
            before = 'new ' + prefix + name + '(context)'
            check(ui_boundaries(before) == 'new ' + replacement + '(context)',
                  'Constructor not localized: ' + before)
            for suffix in ('[]{first, second}', '[3]', '[2][]', '[][]{{first}}',
                           ' /* retain array type */ [] { first }'):
                before = 'new ' + prefix + name + suffix
                check(ui_boundaries(before) == before, 'Array type changed: ' + before)
        for before in ('"new ' + name + '[]{first}"', '// new ' + name + '(context)\n',
                       '/* new ' + name + '(context) */', 'new ' + name + 'Other(context)'):
            check(ui_boundaries(before) == before, 'Non-constructor changed: ' + before)
    for args in ('<>', '<String>', '<java.util.List<String>>'):
        before = 'new ArrayAdapter' + args + '(context, layout, values)'
        check(ui_boundaries(before) == 'new LocalizedArrayAdapter' + args + '(context, layout, values)',
              'Generic constructor not localized: ' + before)
    before = 'for (TextView v : new TextView[]{peak, rms, impact}) { cards.addView(v); }'
    check(ui_boundaries(before) == before, 'PEAK/RMS/IMPACT array narrowed again')
    before = 'TextView peak = new TextView(context); TextView[] rows = new TextView[]{peak};'
    after = 'TextView peak = new LocalizedTextView(context); TextView[] rows = new TextView[]{peak};'
    check(ui_boundaries(before) == after, 'Constructor/array distinction failed')
    before = 'new TextView /* comment: [ ] */ (context)'
    check(ui_boundaries(before) == 'new LocalizedTextView /* comment: [ ] */ (context)',
          'Comment in constructor boundary mishandled')


def main():
    total = [0]
    def check(ok, message):
        total[0] += 1
        if not ok:
            raise AssertionError(message)
    run_checks(check)
    print('UI_CONSTRUCTOR_REGRESSION_PASSED', total[0])


if __name__ == '__main__':
    main()
