-- Align the development administrator with ThingsBoard's default credentials.
update tb_user
set email = 'sysadmin@thingsboard.org'
where id = '00000000-0000-0000-0000-000000000103'
  and email = 'admin@roseboard.local';

update user_credential
set password = '$2a$10$lvDGmxAiahIDNWQRid6Us.y1Cb5dvAY9ERGyaYc9jpo/F0H5WUbom'
where user_id = '00000000-0000-0000-0000-000000000103';
