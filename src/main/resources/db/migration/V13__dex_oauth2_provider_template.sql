insert into oauth2_client_registration_template
    (id, created_time, provider_id, authorization_uri, token_uri, scope, user_info_uri,
     user_name_attribute_name, client_authentication_method, type, comment,
     login_button_label)
values
    ('00000000-0000-0000-0000-000000000104', extract(epoch from now()) * 1000,
     'dex', 'http://localhost:5556/dex/auth', 'http://localhost:5556/dex/token',
     'openid profile email', 'http://localhost:5556/dex/userinfo', 'email',
     'client_secret_basic', 'oidc', 'Local Dex OpenID Connect provider', 'Dex')
on conflict (provider_id) do nothing;
