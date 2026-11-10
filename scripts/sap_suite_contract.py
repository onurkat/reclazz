"""Shared exact default-suite and normal-fixture contract for native drivers."""
import re

DEFAULT_CASES = 46
DEFAULT_OUTCOME = {'PASS': 46, 'FAIL': 0, 'ERROR': 0, 'SKIP': 0}
NORMAL_FIXTURE_FILES = 77
# Report labels from the committed Kotlin scenario constructors; replacements cannot pass by count.
DEFAULT_TEST_NAMES = (
    'Method body change',
    'Add new method',
    'Remove method',
    'Add field',
    'Remove field',
    'Change method signature',
    'Multi-class reload',
    'Constructor change',
    'Bean refresh',
    'MVC re-scan',
    'Cache eviction',
    'Scheduler reload',
    'Event listener',
    'Interceptor reload',
    'ImpEx auto-import',
    'Rapid successive changes',
    'Syntax error recovery',
    'Large class reload',
    'Annotation change',
    'Lambda in reloaded body',
    'Cacheable condition change',
    'Enum tail removal',
    'Interceptor mapping add',
    'Interceptor mapping edit',
    'Spring XML property',
    'Spring XML reference',
    'Spring XML constructor',
    'Spring XML new bean',
    'OCC field set',
    'OCC property rollback',
    'OCC request mapping',
    'OCC priority winner',
    'Platform config',
    'Localization cache',
    'Process XML edit and malformed refusal',
    'New process file first',
    'New process bean first and recovery',
    'Native CronJobService execution',
    'Native EventService publish',
    'ImpEx REMOVE refusal',
    'Populator through OCC',
    'Validator body',
    'Validator constraint parameter',
    'DAO FlexibleSearch query body',
    'Dynamic attribute handler',
    'Native PrepareInterceptor save',
)


def default_result(text):
    plain = re.sub(r'\x1b\[[0-9;]*m', '', text)
    rows = re.findall(r'^([^|\n]+)\|\s*(PASS|FAIL|ERROR|SKIP)\s*\|[^\n]*$', plain, re.M)
    counts = {status: sum(value == status for _, value in rows) for status in DEFAULT_OUTCOME}
    names = [name.strip() for name, _ in rows]
    headers = re.findall(r'^\s*Default committed membership: (\d+) cases(?: \(Orika rollback included\))?\s*$', plain, re.M)
    accepted = (headers == [str(DEFAULT_CASES)] and len(names) == DEFAULT_CASES and
                set(names) == set(DEFAULT_TEST_NAMES) and counts == DEFAULT_OUTCOME and
                not any(status == 'SKIP' for _, status in rows))
    return {'counts': counts, 'defaultSuite': accepted}
