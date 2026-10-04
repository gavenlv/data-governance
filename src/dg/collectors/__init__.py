"""采集框架（连接器实现放本包）。"""

from dg.collectors.base import (  # noqa: F401
    CollectionRun,
    RawColumn,
    RawDataset,
    Source,
    run_collection,
)
from dg.collectors.guard import GuardConfig, GuardDecision, evaluate_guard  # noqa: F401
