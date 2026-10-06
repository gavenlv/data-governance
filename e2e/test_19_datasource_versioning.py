"""组 24（数据源管理）与组 25（资产版本管理）的 BDD 入口。

凭据加密、脱敏、留空保持原值、版本时间线与回滚语义，都在这里从 HTTP 层钉死。
"""

from pytest_bdd import scenarios

scenarios("features/19_datasource_versioning.feature")