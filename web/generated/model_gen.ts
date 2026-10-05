// AUTO-GENERATED FILE — DO NOT EDIT BY HAND.
// Source of truth: model/**.yaml
// Regenerate: python -m dg.cli codegen --target typescript
// Generated at 2026-10-04 14:57:16Z (timestamp line is ignored by --check)

export const ENTITY_TYPES = [
  'Column',
  'Container',
  'Dashboard',
  'DataContract',
  'Dataset',
  'Domain',
  'GlossaryTerm',
  'Metric',
  'Pipeline',
  'Platform',
  'QualityRule',
  'Tag',
  'Team',
  'User',
] as const;
export type EntityType = (typeof ENTITY_TYPES)[number];

export const ASPECT_TYPES = [
  'classification',
  'columnProfile',
  'contractSpec',
  'dashboardSpec',
  'datasetSchema',
  'descriptions',
  'lifecycle',
  'metricSpec',
  'ownership',
  'ruleSpec',
  'tags',
  'termSpec',
  'trustLevel',
] as const;
export type AspectType = (typeof ASPECT_TYPES)[number];

export const RELATIONSHIP_CATEGORIES: Record<string, 'COMPOSITION' | 'AGGREGATION' | 'ASSOCIATION'> = {
  appliesTo: 'ASSOCIATION',
  consumedBy: 'ASSOCIATION',
  contains: 'COMPOSITION',
  derivesFrom: 'ASSOCIATION',
  mappedToTerm: 'ASSOCIATION',
  ownedBy: 'ASSOCIATION',
  partOfDomain: 'AGGREGATION',
  readsFrom: 'ASSOCIATION',
  taggedWith: 'ASSOCIATION',
  writesTo: 'ASSOCIATION',
};

export const LINEAGE_RELATIONSHIPS: readonly string[] = ["consumedBy", "derivesFrom"];

export const ENTITY_ASPECTS: Record<EntityType, readonly string[]> = {
  Column: ["descriptions", "tags", "classification", "columnProfile"],
  Container: ["descriptions", "tags", "ownership", "lifecycle"],
  Dashboard: ["descriptions", "tags", "ownership", "lifecycle", "trustLevel", "dashboardSpec"],
  DataContract: ["descriptions", "tags", "ownership", "lifecycle", "contractSpec"],
  Dataset: ["descriptions", "tags", "ownership", "datasetSchema", "classification", "lifecycle", "trustLevel"],
  Domain: ["descriptions", "ownership"],
  GlossaryTerm: ["descriptions", "tags", "ownership", "termSpec"],
  Metric: ["descriptions", "tags", "ownership", "lifecycle", "metricSpec"],
  Pipeline: ["descriptions", "tags", "ownership", "lifecycle"],
  Platform: ["descriptions", "tags", "ownership"],
  QualityRule: ["descriptions", "tags", "ownership", "lifecycle", "ruleSpec"],
  Tag: ["descriptions"],
  Team: ["descriptions"],
  User: ["descriptions"],
};

// ---- aspect 视图 ----

export interface Classification {
  level?: 'L1' | 'L2' | 'L3' | 'L4';
  categories?: string[];
  piiTypes?: string[];
  appliedBy?: Record<string, unknown>;
  propagation?: Record<string, unknown>;
  reviewedBy?: string;
}

export interface ColumnProfile {
  rowCount?: number;
  nullRatio?: number;
  distinctCount?: number;
  minValue?: string;
  maxValue?: string;
  sampledAt?: string;
  precision?: 'ESTIMATED' | 'EXACT';
  precisionSource?: string;
}

export interface ContractSpec {
  apiVersion: string;
  kind?: string;
  contractVersion: string;
  status?: 'DRAFT' | 'PROPOSED' | 'ACTIVE' | 'DEPRECATED' | 'RETIRED';
  compatibility?: 'BACKWARD' | 'FORWARD' | 'FULL' | 'NONE';
  schema?: Record<string, unknown>;
  primaryKey?: string[];
  quality?: unknown[];
  sla?: Record<string, unknown>;
  semantic?: Record<string, unknown>;
  access?: Record<string, unknown>;
  examples?: unknown[];
  domain?: string;
}

export interface DashboardSpec {
  externalId: string;
  specSource?: 'superset' | 'tableau' | 'metabase' | 'powerbi' | 'other';
  url?: string;
  chartCount?: number;
  charts?: unknown[];
  datasetUrns?: string[];
  published?: boolean;
  certified?: boolean;
  lastRefreshedAt?: string;
}

export interface DatasetSchema {
  fields?: unknown[];
  primaryKey?: string[];
  partitionKeys?: unknown[];
  schemaHash?: string;
  rawTypeSystem?: string;
}

export interface Descriptions {
  text: string;
  language?: string;
  source?: 'MANUAL' | 'IMPORTED' | 'AI_GENERATED' | 'AUTO_COLLECTED';
}

export interface Lifecycle {
  stage?: 'DRAFT' | 'PRODUCTION' | 'DEPRECATED' | 'ARCHIVED';
  deprecationNote?: string;
  replacedBy?: string;
}

export interface MetricSpec {
  metricName: string;
  metricType?: 'SIMPLE' | 'RATIO' | 'DERIVED' | 'CUMULATIVE';
  expression?: string;
  sourceFormat?: 'dg' | 'dbt' | 'cube';
  physicalColumns?: string[];
  dimensions?: unknown[];
}

export interface Ownership {
  owners?: unknown[];
}

export interface RuleSpec {
  ruleId: string;
  metric: 'null_count' | 'null_ratio' | 'missing_count' | 'distinct_count' | 'duplicate_count' | 'duplicate_ratio' | 'row_count' | 'freshness_seconds' | 'percentile' | 'pattern_match_rate' | 'custom_sql';
  operator?: '=' | '!=' | '>' | '>=' | '<' | '<=' | 'between' | 'in';
  threshold?: number;
  thresholdMax?: number;
  column?: string;
  columns?: string[];
  window?: string;
  percentile?: number;
  pattern?: string;
  acceptedValues?: string[];
  customSql?: string;
  expected?: string;
  severity?: 'INFO' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  dimension?: 'completeness' | 'accuracy' | 'consistency' | 'timeliness' | 'uniqueness' | 'validity';
  onFail?: 'BLOCK' | 'ALERT' | 'RECORD';
  schedule?: Record<string, unknown>;
  engineHints?: Record<string, unknown>;
  sourceFrontend?: 'yaml' | 'sql_assertion' | 'dbt_test';
}

export interface Tags {
  tags?: string[];
}

export interface TermSpec {
  definition?: string;
  synonyms?: string[];
  status?: 'DRAFT' | 'APPROVED' | 'DEPRECATED';
  steward?: string;
  relatedTerms?: string[];
  sourceRef?: string;
}

export interface TrustLevel {
  level?: 'CERTIFIED' | 'WARNING' | 'DEPRECATED' | 'UNVERIFIED';
  certifiedBy?: string;
  certifiedAt?: string;
  reason?: string;
}
