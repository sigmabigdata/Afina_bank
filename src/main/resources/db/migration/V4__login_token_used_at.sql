-- Окно повторного использования magic-link (для обхода email-prefetch)
ALTER TABLE users ADD COLUMN IF NOT EXISTS login_token_used_at TIMESTAMPTZ;

COMMENT ON COLUMN users.login_token_used_at IS
    'Первый момент использования токена; окно повторного использования — 10 минут';
