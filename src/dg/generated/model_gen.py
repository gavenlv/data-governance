# AUTO-GENERATED FILE — DO NOT EDIT BY HAND.
# Source of truth: model/**.yaml
# Regenerate: python -m dg.cli codegen --target python
# Generated at 2026-10-04 14:09:42Z (timestamp line is ignored by --check)
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal

# ---------------------------------------------------------------------------
# 实体类型
# ---------------------------------------------------------------------------
ENTITY_TYPES: tuple[str, ...] = ('Platform', 'Container', 'Dataset', 'Column', 'Pipeline', 'Dashboard', 'GlossaryTerm', 'Tag', 'Team', 'User', 'Domain', 'DataContract', 'QualityRule')

ENTITY_ASPECTS: dict[str, tuple[str, ...]] = {
    'Column': ('descriptions', 'tags', 'classification', 'columnProfile'),
    'Container': ('descriptions', 'tags', 'ownership', 'lifecycle'),
    'Dashboard': ('descriptions', 'tags', 'ownership', 'lifecycle', 'trustLevel', 'dashboardSpec'),
    'DataContract': ('descriptions', 'tags', 'ownership', 'lifecycle', 'contractSpec'),
    'Dataset': ('descriptions', 'tags', 'ownership', 'datasetSchema', 'classification', 'lifecycle', 'trustLevel'),
    'Domain': ('descriptions', 'ownership'),
    'GlossaryTerm': ('descriptions', 'tags', 'ownership'),
    'Pipeline': ('descriptions', 'tags', 'ownership', 'lifecycle'),
    'Platform': ('descriptions', 'tags', 'ownership'),
    'QualityRule': ('descriptions', 'tags', 'ownership', 'lifecycle', 'ruleSpec'),
    'Tag': ('descriptions',),
    'Team': ('descriptions',),
    'User': ('descriptions',),
}

ENTITY_PARENTS: dict[str, tuple[str, ...]] = {
    'Column': ('Dataset',),
    'Container': ('Platform',),
    'Dashboard': ('Platform',),
    'DataContract': (),
    'Dataset': ('Container',),
    'Domain': (),
    'GlossaryTerm': (),
    'Pipeline': ('Platform',),
    'Platform': (),
    'QualityRule': (),
    'Tag': (),
    'Team': (),
    'User': (),
}

# ---------------------------------------------------------------------------
# Aspect 类型与字段规格：(字段名, 类型, 是否必填)
# ---------------------------------------------------------------------------
ASPECT_TYPES: tuple[str, ...] = ('descriptions', 'tags', 'ownership', 'datasetSchema', 'classification', 'lifecycle', 'trustLevel', 'columnProfile', 'contractSpec', 'ruleSpec', 'dashboardSpec')

ASPECT_PROPERTIES: dict[str, tuple[tuple[str, str, bool], ...]] = {
    'classification': (('level', 'enum', False), ('categories', 'array', False), ('piiTypes', 'array', False), ('appliedBy', 'object', False), ('propagation', 'object', False), ('reviewedBy', 'string', False)),
    'columnProfile': (('rowCount', 'integer', False), ('nullRatio', 'number', False), ('distinctCount', 'integer', False), ('minValue', 'string', False), ('maxValue', 'string', False), ('sampledAt', 'string', False), ('precision', 'enum', False), ('precisionSource', 'string', False)),
    'contractSpec': (('apiVersion', 'string', True), ('kind', 'string', False), ('contractVersion', 'string', True), ('status', 'enum', False), ('compatibility', 'enum', False), ('schema', 'object', False), ('primaryKey', 'array', False), ('quality', 'array', False), ('sla', 'object', False), ('semantic', 'object', False), ('access', 'object', False), ('examples', 'array', False), ('domain', 'string', False)),
    'dashboardSpec': (('externalId', 'string', True), ('specSource', 'enum', False), ('url', 'string', False), ('chartCount', 'integer', False), ('charts', 'array', False), ('datasetUrns', 'array', False), ('published', 'boolean', False), ('certified', 'boolean', False), ('lastRefreshedAt', 'string', False)),
    'datasetSchema': (('fields', 'array', False), ('primaryKey', 'array', False), ('partitionKeys', 'array', False), ('schemaHash', 'string', False), ('rawTypeSystem', 'string', False)),
    'descriptions': (('text', 'string', True), ('language', 'string', False), ('source', 'enum', False)),
    'lifecycle': (('stage', 'enum', False), ('deprecationNote', 'string', False), ('replacedBy', 'string', False)),
    'ownership': (('owners', 'array', False),),
    'ruleSpec': (('ruleId', 'string', True), ('metric', 'enum', True), ('operator', 'enum', False), ('threshold', 'number', False), ('thresholdMax', 'number', False), ('column', 'string', False), ('columns', 'array', False), ('window', 'string', False), ('percentile', 'number', False), ('pattern', 'string', False), ('acceptedValues', 'array', False), ('customSql', 'string', False), ('expected', 'string', False), ('severity', 'enum', False), ('dimension', 'enum', False), ('onFail', 'enum', False), ('schedule', 'object', False), ('engineHints', 'object', False), ('sourceFrontend', 'enum', False)),
    'tags': (('tags', 'array', False),),
    'trustLevel': (('level', 'enum', False), ('certifiedBy', 'string', False), ('certifiedAt', 'string', False), ('reason', 'string', False)),
}

ASPECT_SOURCE_TRACKED: dict[str, bool] = {
    'classification': True,
    'columnProfile': False,
    'contractSpec': False,
    'dashboardSpec': True,
    'datasetSchema': False,
    'descriptions': True,
    'lifecycle': False,
    'ownership': True,
    'ruleSpec': True,
    'tags': True,
    'trustLevel': True,
}

# ---------------------------------------------------------------------------
# 关系类型
# ---------------------------------------------------------------------------
RELATIONSHIP_CATEGORIES: dict[str, str] = {
    'appliesTo': 'ASSOCIATION',
    'consumedBy': 'ASSOCIATION',
    'contains': 'COMPOSITION',
    'derivesFrom': 'ASSOCIATION',
    'mappedToTerm': 'ASSOCIATION',
    'ownedBy': 'ASSOCIATION',
    'partOfDomain': 'AGGREGATION',
    'readsFrom': 'ASSOCIATION',
    'taggedWith': 'ASSOCIATION',
    'writesTo': 'ASSOCIATION',
}
LINEAGE_RELATIONSHIPS: tuple[str, ...] = ('consumedBy', 'derivesFrom')

RELATIONSHIP_ENDS: dict[str, tuple[tuple[str, ...], tuple[str, ...]]] = {
    'appliesTo': (('DataContract', 'QualityRule'), ('Dataset', 'Column')),
    'consumedBy': (('Dataset', 'Column'), ('Dashboard', 'Pipeline')),
    'contains': (('Platform', 'Container', 'Dataset'), ('Container', 'Dataset', 'Column')),
    'derivesFrom': (('Dataset', 'Column'), ('Dataset', 'Column')),
    'mappedToTerm': (('Dataset', 'Column'), ('GlossaryTerm',)),
    'ownedBy': (('Platform', 'Container', 'Dataset', 'Column', 'Pipeline', 'Dashboard', 'GlossaryTerm', 'Domain'), ('User', 'Team')),
    'partOfDomain': (('Dataset', 'Dashboard', 'Pipeline', 'GlossaryTerm'), ('Domain',)),
    'readsFrom': (('Pipeline', 'Dashboard'), ('Dataset',)),
    'taggedWith': (('Platform', 'Container', 'Dataset', 'Column', 'Pipeline', 'Dashboard', 'GlossaryTerm'), ('Tag',)),
    'writesTo': (('Pipeline',), ('Dataset',)),
}

# ---------------------------------------------------------------------------
# 常用 aspect 的类型化视图（可直接用于构造与校验）
# ---------------------------------------------------------------------------

@dataclass
class Classification:
    """分类分级"""

    level: Literal['L1', 'L2', 'L3', 'L4'] = 'L2'
    categories: list[str] = field(default_factory=list)
    pii_types: list[str] = field(default_factory=list)
    applied_by: dict[str, Any] = field(default_factory=dict)
    propagation: dict[str, Any] = field(default_factory=dict)
    reviewed_by: str = ''

@dataclass
class ColumnProfile:
    """列画像（时序类）"""

    row_count: int = 0
    null_ratio: float = 0
    distinct_count: int = 0
    min_value: str = ''
    max_value: str = ''
    sampled_at: str = ''
    precision: Literal['ESTIMATED', 'EXACT'] = 'ESTIMATED'
    precision_source: str = ''

@dataclass
class ContractSpec:
    """契约定义（ODCS 兼容）"""

    api_version: str
    contract_version: str
    kind: str = 'DataContract'
    status: Literal['DRAFT', 'PROPOSED', 'ACTIVE', 'DEPRECATED', 'RETIRED'] = 'DRAFT'
    compatibility: Literal['BACKWARD', 'FORWARD', 'FULL', 'NONE'] = 'BACKWARD'
    schema: dict[str, Any] = field(default_factory=dict)
    primary_key: list[str] = field(default_factory=list)
    quality: list[Any] = field(default_factory=list)
    sla: dict[str, Any] = field(default_factory=dict)
    semantic: dict[str, Any] = field(default_factory=dict)
    access: dict[str, Any] = field(default_factory=dict)
    examples: list[Any] = field(default_factory=list)
    domain: str = ''

@dataclass
class DashboardSpec:
    """仪表板规格（BI 平台）"""

    external_id: str
    spec_source: Literal['superset', 'tableau', 'metabase', 'powerbi', 'other'] = 'superset'
    url: str = ''
    chart_count: int = 0
    charts: list[Any] = field(default_factory=list)
    dataset_urns: list[str] = field(default_factory=list)
    published: bool = False
    certified: bool = False
    last_refreshed_at: str = ''

@dataclass
class DatasetSchema:
    """结构"""

    fields: list[Any] = field(default_factory=list)
    primary_key: list[str] = field(default_factory=list)
    partition_keys: list[Any] = field(default_factory=list)
    schema_hash: str = ''
    raw_type_system: str = ''

@dataclass
class Descriptions:
    """描述"""

    text: str
    language: str = 'zh'
    source: Literal['MANUAL', 'IMPORTED', 'AI_GENERATED', 'AUTO_COLLECTED'] = 'MANUAL'

@dataclass
class Lifecycle:
    """生命周期"""

    stage: Literal['DRAFT', 'PRODUCTION', 'DEPRECATED', 'ARCHIVED'] = 'PRODUCTION'
    deprecation_note: str = ''
    replaced_by: str = ''

@dataclass
class Ownership:
    """所有权"""

    owners: list[Any] = field(default_factory=list)

@dataclass
class RuleSpec:
    """质量规则定义（统一 IR）"""

    rule_id: str
    metric: Literal['null_count', 'null_ratio', 'missing_count', 'distinct_count', 'duplicate_count', 'duplicate_ratio', 'row_count', 'freshness_seconds', 'percentile', 'pattern_match_rate', 'custom_sql']
    operator: Literal['=', '!=', '>', '>=', '<', '<=', 'between', 'in'] = '='
    threshold: float = 0
    threshold_max: float = 0
    column: str = ''
    columns: list[str] = field(default_factory=list)
    window: str = ''
    percentile: float = 0
    pattern: str = ''
    accepted_values: list[str] = field(default_factory=list)
    custom_sql: str = ''
    expected: str = ''
    severity: Literal['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] = 'MEDIUM'
    dimension: Literal['completeness', 'accuracy', 'consistency', 'timeliness', 'uniqueness', 'validity'] = 'validity'
    on_fail: Literal['BLOCK', 'ALERT', 'RECORD'] = 'ALERT'
    schedule: dict[str, Any] = field(default_factory=dict)
    engine_hints: dict[str, Any] = field(default_factory=dict)
    source_frontend: Literal['yaml', 'sql_assertion', 'dbt_test'] = 'yaml'

@dataclass
class Tags:
    """标签"""

    tags: list[str] = field(default_factory=list)

@dataclass
class TrustLevel:
    """可信状态"""

    level: Literal['CERTIFIED', 'WARNING', 'DEPRECATED', 'UNVERIFIED'] = 'UNVERIFIED'
    certified_by: str = ''
    certified_at: str = ''
    reason: str = ''
