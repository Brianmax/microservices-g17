--liquibase formatted sql
--changeset virtual-bank:identity-001
CREATE TABLE credentials (
    user_id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(72) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    auth_version BIGINT NOT NULL DEFAULT 1 CHECK (auth_version > 0),
    password_changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE roles (id UUID PRIMARY KEY, code VARCHAR(40) NOT NULL UNIQUE, description VARCHAR(255) NOT NULL);
CREATE TABLE permissions (id UUID PRIMARY KEY, code VARCHAR(80) NOT NULL UNIQUE, description VARCHAR(255) NOT NULL);
CREATE TABLE user_roles (user_id UUID NOT NULL REFERENCES credentials(user_id) ON DELETE CASCADE, role_id UUID NOT NULL REFERENCES roles(id), PRIMARY KEY(user_id, role_id));
CREATE TABLE role_permissions (role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE, permission_id UUID NOT NULL REFERENCES permissions(id), PRIMARY KEY(role_id, permission_id));
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES credentials(user_id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE, family_id UUID NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked_at TIMESTAMP WITH TIME ZONE,
    replaced_by_id UUID REFERENCES refresh_tokens(id), created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);
INSERT INTO roles VALUES ('10000000-0000-0000-0000-000000000001','CUSTOMER','Customer'),('10000000-0000-0000-0000-000000000002','SUPPORT','Support'),('10000000-0000-0000-0000-000000000003','ADMIN','Administrator');
INSERT INTO permissions (id,code,description) VALUES
('20000000-0000-0000-0000-000000000001','user:create:any','Create users'),
('20000000-0000-0000-0000-000000000002','user:read:self','Read own profile'),
('20000000-0000-0000-0000-000000000003','user:read:any','Read any user'),
('20000000-0000-0000-0000-000000000004','user:update:self','Update own profile'),
('20000000-0000-0000-0000-000000000005','user:update:any','Update any user'),
('20000000-0000-0000-0000-000000000006','user:deactivate:any','Deactivate users'),
('20000000-0000-0000-0000-000000000007','account:create:self','Open own account'),
('20000000-0000-0000-0000-000000000008','account:create:any','Open any account'),
('20000000-0000-0000-0000-000000000009','account:read:self','Read own accounts'),
('20000000-0000-0000-0000-000000000010','account:read:any','Read any account'),
('20000000-0000-0000-0000-000000000011','account:freeze:any','Freeze accounts'),
('20000000-0000-0000-0000-000000000012','account:unfreeze:any','Unfreeze accounts'),
('20000000-0000-0000-0000-000000000013','account:close:self','Close own account'),
('20000000-0000-0000-0000-000000000014','account:close:any','Close any account'),
('20000000-0000-0000-0000-000000000015','deposit:create:self','Deposit to own account'),
('20000000-0000-0000-0000-000000000016','deposit:create:any','Deposit to any account'),
('20000000-0000-0000-0000-000000000017','withdrawal:create:self','Withdraw from own account'),
('20000000-0000-0000-0000-000000000018','withdrawal:create:any','Withdraw from any account'),
('20000000-0000-0000-0000-000000000019','transfer:create:self','Create own transfer'),
('20000000-0000-0000-0000-000000000020','transfer:create:any','Create any transfer'),
('20000000-0000-0000-0000-000000000021','transfer:read:self','Read own transfers'),
('20000000-0000-0000-0000-000000000022','transfer:read:any','Read any transfer'),
('20000000-0000-0000-0000-000000000023','transaction:read:self','Read own transactions'),
('20000000-0000-0000-0000-000000000024','transaction:read:any','Read any transaction'),
('20000000-0000-0000-0000-000000000025','role:read:any','Read roles'),
('20000000-0000-0000-0000-000000000026','role:assign:any','Assign roles'),
('20000000-0000-0000-0000-000000000027','permission:read:any','Read permissions');
INSERT INTO role_permissions (role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code='CUSTOMER' AND p.code IN ('user:read:self','user:update:self','account:create:self','account:read:self','account:close:self','deposit:create:self','withdrawal:create:self','transfer:create:self','transfer:read:self','transaction:read:self'))
   OR (r.code='SUPPORT' AND p.code IN ('user:read:any','account:read:any','account:freeze:any','account:unfreeze:any','transfer:read:any','transaction:read:any'))
   OR r.code='ADMIN';
