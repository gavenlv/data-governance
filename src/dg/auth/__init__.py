"""认证与授权。"""

from dg.auth.policy import DEFAULT_POLICY, Policy  # noqa: F401
from dg.auth.principal import (  # noqa: F401
    ANONYMOUS,
    Principal,
    ROLE_ADMIN,
    ROLE_EDITOR,
    ROLE_READER,
    ROLE_STEWARD,
    normalize_roles,
)
from dg.auth.tokens import (  # noqa: F401
    AuthError,
    Authenticator,
    DEV_TOKENS,
    Forbidden,
    Unauthenticated,
    get_authenticator,
    reset_authenticator,
)
