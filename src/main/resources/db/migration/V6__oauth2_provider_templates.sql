insert into oauth2_client_registration_template
    (id, created_time, provider_id, authorization_uri, token_uri, scope, user_info_uri,
     user_name_attribute_name, client_authentication_method, type, comment,
     login_button_label)
values
    ('00000000-0000-0000-0000-000000000101', extract(epoch from now()) * 1000,
     'google', 'https://accounts.google.com/o/oauth2/v2/auth',
     'https://oauth2.googleapis.com/token', 'openid profile email',
     'https://openidconnect.googleapis.com/v1/userinfo', 'email',
     'client_secret_basic', 'google', 'Built-in Google OpenID Connect provider', 'Google'),
    ('00000000-0000-0000-0000-000000000102', extract(epoch from now()) * 1000,
     'github', 'https://github.com/login/oauth/authorize',
     'https://github.com/login/oauth/access_token', 'read:user user:email',
     'https://api.github.com/user', 'email',
     'client_secret_post', 'github', 'Built-in GitHub OAuth provider', 'GitHub'),
    ('00000000-0000-0000-0000-000000000103', extract(epoch from now()) * 1000,
     'oidc', null, null, 'openid profile email', null, 'email',
     'client_secret_basic', 'oidc', 'Generic OpenID Connect provider', 'OpenID Connect')
on conflict (provider_id) do nothing;
