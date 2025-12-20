# 🛠️ Guía de Implementación - Mejoras Críticas

## 📋 Índice de Mejoras Prioritarias

1. [Dead Letter Queue con Admin UI](#1-dead-letter-queue-con-admin-ui)
2. [Idempotency Keys en Webhooks](#2-idempotency-keys-en-webhooks)
3. [Health Checks Profundos](#3-health-checks-profundos)
4. [Rate Limiting con Redis](#4-rate-limiting-con-redis)
5. [Suite de Tests con Jest](#5-suite-de-tests-con-jest)

---

## 1. Dead Letter Queue con Admin UI

### Problema Actual
```javascript
// outbox.service.js línea 284
outbox.estado = 'dead_letter';  // Solo marca, no hay forma de recuperar
```

### Paso 1: Crear Rutas Admin para DLQ

**Archivo nuevo:** `src/routes/dlq.routes.js`
```javascript
const express = require('express');
const router = express.Router();
const dlqController = require('../controllers/dlq.controller');
const { authenticateAdmin } = require('../middleware/auth.middleware');

// Listar eventos en DLQ
router.get('/', authenticateAdmin, dlqController.listar);

// Reencolar un evento específico
router.post('/:id/retry', authenticateAdmin, dlqController.retry);

// Reencolar todos los eventos DLQ
router.post('/retry-all', authenticateAdmin, dlqController.retryAll);

// Descartar evento (borrado lógico)
router.delete('/:id', authenticateAdmin, dlqController.descartar);

module.exports = router;
```

### Paso 2: Crear Controlador DLQ

**Archivo nuevo:** `src/controllers/dlq.controller.js`
```javascript
const Outbox = require('../models/outbox.model');
const logger = require('../logger');

class DLQController {
  async listar(req, res) {
    try {
      const { page = 1, limit = 20 } = req.query;
      const offset = (page - 1) * limit;

      const { count, rows } = await Outbox.findAndCountAll({
        where: { estado: 'dead_letter' },
        order: [['fecha_dead_letter', 'DESC']],
        limit: parseInt(limit),
        offset: parseInt(offset)
      });

      res.json({
        total: count,
        page: parseInt(page),
        totalPages: Math.ceil(count / limit),
        eventos: rows.map(e => ({
          id: e.id,
          admision_estado_id: e.admision_estado_id,
          evento_tipo: e.evento_tipo,
          intentos: e.intentos,
          error_mensaje: e.error_mensaje,
          error_categoria: e.error_categoria,
          fecha_dead_letter: e.fecha_dead_letter,
          datos_evento: e.datos_evento
        }))
      });
    } catch (error) {
      logger.error('Error listando DLQ:', error);
      res.status(500).json({ error: 'Error al listar DLQ' });
    }
  }

  async retry(req, res) {
    try {
      const { id } = req.params;
      const evento = await Outbox.findByPk(id);

      if (!evento) {
        return res.status(404).json({ error: 'Evento no encontrado' });
      }

      if (evento.estado !== 'dead_letter') {
        return res.status(400).json({ error: 'Solo se puede reencolar eventos DLQ' });
      }

      await evento.update({
        estado: 'pendiente',
        intentos: 0,
        proximo_intento: null,
        error_mensaje: null,
        error_categoria: null
      });

      logger.info(`DLQ reencolado: ID=${id}`);
      res.json({ message: 'Evento reencolado exitosamente', evento });
    } catch (error) {
      logger.error('Error reencolando evento:', error);
      res.status(500).json({ error: 'Error al reencolar' });
    }
  }

  async retryAll(req, res) {
    try {
      const result = await Outbox.update(
        { 
          estado: 'pendiente',
          intentos: 0,
          proximo_intento: null,
          error_mensaje: null
        },
        { where: { estado: 'dead_letter' } }
      );

      logger.info(`DLQ: ${result[0]} eventos reencolados`);
      res.json({ message: `${result[0]} eventos reencolados` });
    } catch (error) {
      logger.error('Error reencolando todos:', error);
      res.status(500).json({ error: 'Error al reencolar todos' });
    }
  }

  async descartar(req, res) {
    try {
      const { id } = req.params;
      await Outbox.update(
        { estado: 'descartado' },
        { where: { id, estado: 'dead_letter' } }
      );

      logger.info(`DLQ descartado: ID=${id}`);
      res.json({ message: 'Evento descartado' });
    } catch (error) {
      logger.error('Error descartando evento:', error);
      res.status(500).json({ error: 'Error al descartar' });
    }
  }
}

module.exports = new DLQController();
```

### Paso 3: Registrar Rutas

**Modificar:** `src/routes/index.js`
```javascript
const dlqRoutes = require('./dlq.routes');

// ... otras rutas

// Rutas DLQ (protegidas con ADMIN_TOKEN)
router.use('/admin/dlq', dlqRoutes);
```

### Paso 4: Actualizar Modelo Outbox

**Modificar:** `src/models/outbox.model.js` línea 35
```javascript
estado: {
  type: DataTypes.ENUM('pendiente', 'enviado', 'error', 'dead_letter', 'descartado'),
  // Agregar 'descartado' al enum
}
```

### Paso 5: Migración BD

**Archivo nuevo:** `src/migrations/YYYYMMDDHHMMSS-add-descartado-to-outbox.js`
```javascript
module.exports = {
  up: async (queryInterface, Sequelize) => {
    await queryInterface.sequelize.query(`
      ALTER TYPE enum_outbox_estado ADD VALUE 'descartado';
    `);
  },

  down: async (queryInterface, Sequelize) => {
    // No se puede eliminar valores de ENUM sin recrear el tipo
  }
};
```

### Probar
```bash
# 1. Aplicar migración
npx sequelize-cli db:migrate

# 2. Crear evento DLQ de prueba (simular)
curl -X GET http://localhost:3001/admin/dlq \
  -H "Authorization: Bearer <ADMIN_TOKEN>"

# 3. Reencolar
curl -X POST http://localhost:3001/admin/dlq/123/retry \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
```

---

## 2. Idempotency Keys en Webhooks

### Problema Actual
Sin idempotency key, si el worker se reinicia después de enviar el HTTP pero antes de marcar como enviado, el evento se duplica.

### Paso 1: Generar Idempotency Key

**Modificar:** `src/services/outbox.service.js` línea 328
```javascript
const crypto = require('crypto');

async enviarEventoPush(outbox) {
  const pushServiceUrl = process.env.PUSH_SERVICE_URL;
  
  // Generar key determinística
  const idempotencyKey = crypto
    .createHash('sha256')
    .update(`${outbox.id}-${outbox.created_at.toISOString()}`)
    .digest('hex');

  try {
    const response = await axios.post(
      pushServiceUrl,
      {
        idempotency_key: idempotencyKey,  // ← NUEVO en body
        evento_tipo: outbox.evento_tipo,
        datos: outbox.datos_evento,
        timestamp: new Date().toISOString()
      },
      {
        timeout: timeoutMs,
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${process.env.PUSH_API_TOKEN}`,
          'X-Idempotency-Key': idempotencyKey  // ← NUEVO en header
        }
      }
    );
    // ... resto igual
  }
}
```

### Paso 2: Guardar Key en BD (Opcional pero Recomendado)

**Modificar:** `src/models/outbox.model.js`
```javascript
idempotency_key: {
  type: DataTypes.STRING(64),
  allowNull: true,
  unique: true,
  comment: 'SHA256 hash para deduplicación en cliente'
}
```

**Migración:**
```javascript
// src/migrations/YYYYMMDDHHMMSS-add-idempotency-key.js
module.exports = {
  up: async (queryInterface, Sequelize) => {
    await queryInterface.addColumn('outbox', 'idempotency_key', {
      type: Sequelize.STRING(64),
      allowNull: true,
      unique: true
    });
    
    // Crear índice
    await queryInterface.addIndex('outbox', ['idempotency_key']);
  },
  
  down: async (queryInterface) => {
    await queryInterface.removeColumn('outbox', 'idempotency_key');
  }
};
```

### Paso 3: Generar y Guardar al Crear

**Modificar:** `src/services/outbox.service.js` línea 23
```javascript
async crearEventoOutbox(admisionEstadoId, eventoTipo, datosHomologados, options = {}) {
  const idempotencyKey = crypto
    .createHash('sha256')
    .update(`${admisionEstadoId}-${eventoTipo}-${Date.now()}`)
    .digest('hex');

  const outbox = await Outbox.create({
    admision_estado_id: admisionEstadoId,
    evento_tipo: eventoTipo,
    datos_evento: datosHomologados,
    estado: 'pendiente',
    intentos: 0,
    max_intentos: options.maxIntentos || 3,
    idempotency_key: idempotencyKey,  // ← NUEVO
    ...options
  });
  // ...
}
```

### Probar
```bash
# El cliente debe implementar caché de idempotency keys
# Ejemplo en test-server.js:

const processedKeys = new Set();

app.post('/push', (req, res) => {
  const key = req.headers['x-idempotency-key'];
  
  if (processedKeys.has(key)) {
    console.log('Duplicado detectado:', key);
    return res.status(200).json({ message: 'Ya procesado' });
  }
  
  processedKeys.add(key);
  // Procesar evento...
  res.json({ success: true });
});
```

---

## 3. Health Checks Profundos

### Paso 1: Crear Servicio de Health

**Archivo nuevo:** `src/services/health.service.js`
```javascript
const { sequelize } = require('../config/sequelize');
const rabbitmqService = require('./rabbitmq.service');
const { getCircuitBreaker } = require('./circuitBreaker.service');
const logger = require('../logger');

class HealthService {
  async checkDatabase() {
    try {
      await sequelize.authenticate();
      const result = await sequelize.query('SELECT 1+1 AS result');
      return { status: 'healthy', latency: 0 };
    } catch (error) {
      logger.error('Health check DB failed:', error);
      return { status: 'unhealthy', error: error.message };
    }
  }

  async checkRabbitMQ() {
    try {
      const isConnected = await rabbitmqService.isConnected();
      return { 
        status: isConnected ? 'healthy' : 'degraded',
        message: isConnected ? 'Connected' : 'Using polling fallback'
      };
    } catch (error) {
      return { status: 'degraded', error: error.message };
    }
  }

  async checkCircuitBreaker() {
    const cb = getCircuitBreaker('push-service');
    const state = cb.getState();
    
    return {
      status: state.state === 'OPEN' ? 'degraded' : 'healthy',
      state: state.state,
      failureCount: state.failureCount,
      failureRate: state.failureRate
    };
  }

  async getFullHealth() {
    const [db, rabbitmq, circuitBreaker] = await Promise.all([
      this.checkDatabase(),
      this.checkRabbitMQ(),
      this.checkCircuitBreaker()
    ]);

    const isHealthy = db.status === 'healthy';
    const isDegraded = rabbitmq.status === 'degraded' || 
                       circuitBreaker.status === 'degraded';

    return {
      status: !isHealthy ? 'unhealthy' : isDegraded ? 'degraded' : 'healthy',
      timestamp: new Date().toISOString(),
      checks: { database: db, rabbitmq, circuitBreaker }
    };
  }
}

module.exports = new HealthService();
```

### Paso 2: Crear Rutas Health

**Archivo nuevo:** `src/routes/health.routes.js`
```javascript
const express = require('express');
const router = express.Router();
const healthService = require('../services/health.service');

// Full health check (no auth)
router.get('/health', async (req, res) => {
  const health = await healthService.getFullHealth();
  const statusCode = health.status === 'healthy' ? 200 : 503;
  res.status(statusCode).json(health);
});

// Liveness probe (K8s)
router.get('/health/liveness', (req, res) => {
  res.json({ status: 'alive', timestamp: new Date().toISOString() });
});

// Readiness probe (K8s)
router.get('/health/readiness', async (req, res) => {
  const db = await healthService.checkDatabase();
  if (db.status === 'healthy') {
    res.json({ status: 'ready' });
  } else {
    res.status(503).json({ status: 'not_ready', reason: db.error });
  }
});

module.exports = router;
```

### Paso 3: Registrar Rutas

**Modificar:** `src/routes/index.js`
```javascript
const healthRoutes = require('./health.routes');

// Rutas de salud (públicas, sin auth)
router.use('/', healthRoutes);

// Eliminar la ruta simple anterior:
// router.get('/health', (req, res) => ...);
```

### Paso 4: Agregar isConnected a RabbitMQ Service

**Modificar:** `src/services/rabbitmq.service.js`
```javascript
class RabbitMQService {
  // ... métodos existentes

  async isConnected() {
    try {
      return this.connection && !this.connection.closed;
    } catch {
      return false;
    }
  }

  async ping() {
    if (!this.channel) throw new Error('No channel');
    // Verificar que el channel esté vivo
    await this.channel.checkQueue('ping-test');
    return true;
  }
}
```

### Paso 5: Configurar K8s (Opcional)

```yaml
# deployment.yaml
spec:
  containers:
  - name: middleware
    livenessProbe:
      httpGet:
        path: /health/liveness
        port: 3001
      initialDelaySeconds: 30
      periodSeconds: 10
    readinessProbe:
      httpGet:
        path: /health/readiness
        port: 3001
      initialDelaySeconds: 10
      periodSeconds: 5
```

### Probar
```bash
curl http://localhost:3001/health
# Esperado: {"status":"healthy","timestamp":"...","checks":{...}}

# Simular BD caída
docker stop postgres_middleware
curl http://localhost:3001/health
# Esperado: 503 {"status":"unhealthy",...}
```

---

## 4. Rate Limiting con Redis

### Paso 1: Agregar Redis a Docker Compose

**Modificar:** `docker-compose.yml`
```yaml
services:
  redis:
    image: redis:7-alpine
    container_name: trazabilidad_redis
    restart: unless-stopped
    ports:
      - "6379:6379"
    networks:
      - middleware_app_app_net
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  app:
    # ...
    depends_on:
      - redis  # ← Agregar dependencia
    environment:
      REDIS_URL: redis://redis:6379  # ← Nueva variable
```

### Paso 2: Instalar Dependencias

```bash
npm install express-rate-limit rate-limit-redis ioredis
```

### Paso 3: Configurar Cliente Redis

**Archivo nuevo:** `src/config/redis.js`
```javascript
const Redis = require('ioredis');
const logger = require('../logger');

const redisClient = new Redis(process.env.REDIS_URL || 'redis://localhost:6379', {
  retryStrategy(times) {
    const delay = Math.min(times * 50, 2000);
    return delay;
  },
  reconnectOnError(err) {
    logger.error('Redis reconnect error:', err);
    return true;
  }
});

redisClient.on('connect', () => {
  logger.success('✓ Redis connected');
});

redisClient.on('error', (err) => {
  logger.error('Redis error:', err);
});

module.exports = redisClient;
```

### Paso 4: Crear Middleware Rate Limiter

**Archivo nuevo:** `src/middleware/rateLimiter.js`
```javascript
const rateLimit = require('express-rate-limit');
const RedisStore = require('rate-limit-redis');
const redisClient = require('../config/redis');

// Rate limiter para login (5 intentos / 15 minutos)
const loginLimiter = rateLimit({
  store: new RedisStore({
    client: redisClient,
    prefix: 'rl:login:'
  }),
  windowMs: 15 * 60 * 1000,  // 15 minutos
  max: 5,
  message: {
    error: 'Demasiados intentos de login. Intenta en 15 minutos.',
    retryAfter: 15 * 60
  },
  standardHeaders: true,
  legacyHeaders: false,
  keyGenerator: (req) => {
    // Rate limit por IP + username
    const username = req.body.cod_username || req.body.cod_remitente || 'unknown';
    return `${req.ip}-${username}`;
  }
});

// Rate limiter para API general (100 req / minuto por usuario)
const apiLimiter = rateLimit({
  store: new RedisStore({
    client: redisClient,
    prefix: 'rl:api:'
  }),
  windowMs: 1 * 60 * 1000,  // 1 minuto
  max: 100,
  message: {
    error: 'Límite de requests excedido. Intenta en 1 minuto.',
    retryAfter: 60
  },
  standardHeaders: true,
  legacyHeaders: false,
  keyGenerator: (req) => {
    // Usar user ID si está autenticado, sino IP
    return req.user?.id?.toString() || req.ip;
  },
  skip: (req) => {
    // No aplicar a health checks
    return req.path.startsWith('/health');
  }
});

// Rate limiter estricto para admin (20 req / minuto)
const adminLimiter = rateLimit({
  store: new RedisStore({
    client: redisClient,
    prefix: 'rl:admin:'
  }),
  windowMs: 1 * 60 * 1000,
  max: 20,
  message: { error: 'Límite admin excedido' },
  keyGenerator: (req) => req.ip
});

module.exports = {
  loginLimiter,
  apiLimiter,
  adminLimiter
};
```

### Paso 5: Aplicar Rate Limiters

**Modificar:** `src/routes/auth.routes.js`
```javascript
const { loginLimiter } = require('../middleware/rateLimiter');

router.post('/login', loginLimiter, authController.login);
router.post('/login-ext', loginLimiter, authExtController.login);
```

**Modificar:** `src/routes/admisionEstado.routes.js`
```javascript
const { apiLimiter } = require('../middleware/rateLimiter');

router.post('/trazabilidad/statusCorreos', 
  apiLimiter,
  authenticateToken, 
  admisionEstadoController.create
);

router.get('/trazabilidad/pull/:codAdmision',
  apiLimiter,
  authenticateTokenExt,
  admisionEstadoController.getEstadosByCodAdmision
);
```

**Modificar:** `src/routes/dlq.routes.js`
```javascript
const { adminLimiter } = require('../middleware/rateLimiter');

router.use(adminLimiter);  // Aplicar a todas las rutas admin
```

### Paso 6: Inicializar Redis en Startup

**Modificar:** `src/index.js`
```javascript
const redisClient = require('./config/redis');

async function startServer() {
  try {
    // ... existing code
    
    // Verificar Redis
    try {
      await redisClient.ping();
      logger.success('✓ Redis conectado');
    } catch (err) {
      logger.warning('⚠️  Redis no disponible - rate limiting deshabilitado');
    }
    
    // ... rest of startup
  }
}
```

### Probar
```bash
# 1. Levantar con Redis
docker-compose up -d

# 2. Probar rate limit en login
for i in {1..6}; do
  curl -X POST http://localhost:3001/login \
    -H "Content-Type: application/json" \
    -d '{"cod_username":"test","password":"wrong"}'
  echo ""
done

# El 6to request debe retornar 429:
# {"error":"Demasiados intentos de login..."}

# 3. Ver headers
curl -i http://localhost:3001/trazabilidad/statusCorreos \
  -H "Authorization: Bearer <TOKEN>"

# Headers:
# RateLimit-Limit: 100
# RateLimit-Remaining: 99
# RateLimit-Reset: 1234567890
```

---

## 5. Suite de Tests con Jest

### Paso 1: Instalar Jest y Dependencias

```bash
npm install --save-dev jest supertest @types/jest
npm install --save-dev sequelize-mock nock
```

### Paso 2: Configurar Jest

**Archivo nuevo:** `jest.config.js`
```javascript
module.exports = {
  testEnvironment: 'node',
  coverageDirectory: 'coverage',
  collectCoverageFrom: [
    'src/**/*.js',
    '!src/migrations/**',
    '!src/index.js'
  ],
  testMatch: [
    '**/tests/unit/**/*.test.js',
    '**/tests/integration/**/*.test.js'
  ],
  setupFilesAfterEnv: ['<rootDir>/tests/setup.js'],
  testTimeout: 10000
};
```

### Paso 3: Setup de Tests

**Archivo nuevo:** `tests/setup.js`
```javascript
// Setup global para todos los tests
process.env.NODE_ENV = 'test';
process.env.JWT_SECRET = 'test-secret-key';
process.env.DB_NAME = 'trazabilidad_test';

// Mock logger para tests
jest.mock('../src/logger', () => ({
  info: jest.fn(),
  error: jest.fn(),
  warning: jest.fn(),
  success: jest.fn(),
  debug: jest.fn()
}));
```

### Paso 4: Tests Unitarios - Chaining Service

**Archivo nuevo:** `tests/unit/estadoChaining.service.test.js`
```javascript
const EstadoChainingService = require('../../src/services/admision/estadoChaining.service');
const AdmisionEstado = require('../../src/models/admisionEstado.model');
const EstadoHomologacion = require('../../src/models/estadoHomologacion.model');

// Mock de modelos
jest.mock('../../src/models/admisionEstado.model');
jest.mock('../../src/models/estadoHomologacion.model');

describe('EstadoChainingService', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('procesarEstadoConEncadenamiento', () => {
    it('permite orden=1 sin estados previos', async () => {
      AdmisionEstado.findAll.mockResolvedValue([]);
      EstadoHomologacion.findOne.mockResolvedValue(null);

      const result = await EstadoChainingService.procesarEstadoConEncadenamiento(
        { expedicion: 'TEST001', estado_2: 'ADMITIDO' },
        1
      );

      expect(result.permitirEnvio).toBe(true);
      expect(result.razon).toContain('primer estado');
    });

    it('rechaza orden=3 si falta orden=2', async () => {
      AdmisionEstado.findAll.mockResolvedValue([
        { id: 1, orden: 1, enviado: true, estado_espera: false }
      ]);

      const result = await EstadoChainingService.procesarEstadoConEncadenamiento(
        { expedicion: 'TEST001', estado_2: 'EN_RUTA' },
        3
      );

      expect(result.permitirEnvio).toBe(false);
      expect(result.razon).toContain('Faltan estados con orden: 2');
    });

    it('permite orden=2 si orden=1 ya fue enviado', async () => {
      AdmisionEstado.findAll.mockResolvedValue([
        { id: 1, orden: 1, enviado: true, estado_espera: false }
      ]);

      const result = await EstadoChainingService.procesarEstadoConEncadenamiento(
        { expedicion: 'TEST001', estado_2: 'EN_RUTA' },
        2
      );

      expect(result.permitirEnvio).toBe(true);
      expect(result.razon).toContain('estados previos han sido recibidos y enviados');
    });

    it('rechaza si orden excede máximo configurado', async () => {
      AdmisionEstado.findAll.mockResolvedValue([]);
      EstadoHomologacion.findAll.mockResolvedValue([{ orden: 40 }]);

      const result = await EstadoChainingService.procesarEstadoConEncadenamiento(
        { 
          expedicion: 'TEST001', 
          estado_2: 'INVALIDO',
          codRemitente: 'REM001',
          codCourrier: 'CHL'
        },
        50  // Excede máximo de 40
      );

      expect(result.permitirEnvio).toBe(false);
      expect(result.razon).toContain('excede el máximo permitido');
    });
  });
});
```

### Paso 5: Tests de Integración - API

**Archivo nuevo:** `tests/integration/api.test.js`
```javascript
const request = require('supertest');
const { app } = require('../../src/index');
const { sequelize } = require('../../src/config/sequelize');
const jwt = require('jsonwebtoken');

describe('API Integration Tests', () => {
  let authToken;

  beforeAll(async () => {
    await sequelize.sync({ force: true });
    
    // Crear usuario de prueba y generar token
    authToken = jwt.sign(
      { id: 1, cod_username: 'TEST_USER' },
      process.env.JWT_SECRET,
      { expiresIn: '1h' }
    );
  });

  afterAll(async () => {
    await sequelize.close();
  });

  describe('POST /trazabilidad/statusCorreos', () => {
    it('retorna 401 sin token', async () => {
      const res = await request(app)
        .post('/trazabilidad/statusCorreos')
        .send({ codadmision: 'TEST001' });

      expect(res.status).toBe(401);
    });

    it('crea estado correctamente con token válido', async () => {
      const res = await request(app)
        .post('/trazabilidad/statusCorreos')
        .set('Authorization', `Bearer ${authToken}`)
        .send({
          codadmision: 'TEST001',
          referencia: 'REF001',
          estado: 'entrada',
          estado_2: 'En Bodega',
          fechaEvento: '2024-12-20 10:00:00'
        });

      expect(res.status).toBe(201);
      expect(res.body.estado).toBe(1);
      expect(res.body.data.expedicion).toBe('TEST001');
    });
  });

  describe('GET /health', () => {
    it('retorna status healthy', async () => {
      const res = await request(app).get('/health');

      expect(res.status).toBe(200);
      expect(res.body.status).toMatch(/healthy|degraded/);
      expect(res.body.checks).toHaveProperty('database');
    });
  });
});
```

### Paso 6: Actualizar package.json

```json
{
  "scripts": {
    "test": "jest",
    "test:unit": "jest tests/unit",
    "test:integration": "jest tests/integration",
    "test:watch": "jest --watch",
    "test:coverage": "jest --coverage"
  }
}
```

### Paso 7: Ejecutar Tests

```bash
# Todos los tests
npm test

# Solo unitarios
npm run test:unit

# Con coverage
npm run test:coverage

# Watch mode (desarrollo)
npm run test:watch
```

---

## 📝 Checklist de Implementación

```markdown
- [ ] Mejora 1: DLQ con Admin UI
  - [ ] Crear rutas DLQ
  - [ ] Crear controlador
  - [ ] Actualizar modelo outbox
  - [ ] Migración BD
  - [ ] Probar endpoints

- [ ] Mejora 2: Idempotency Keys
  - [ ] Agregar generación de keys
  - [ ] Modificar enviarEventoPush
  - [ ] Migración BD (columna)
  - [ ] Actualizar test-server para validar

- [ ] Mejora 3: Health Checks
  - [ ] Crear health service
  - [ ] Crear rutas health
  - [ ] Agregar isConnected a RabbitMQ
  - [ ] Probar 3 endpoints

- [ ] Mejora 4: Rate Limiting
  - [ ] Agregar Redis a docker-compose
  - [ ] Instalar dependencias
  - [ ] Crear middleware
  - [ ] Aplicar a rutas
  - [ ] Probar límites

- [ ] Mejora 5: Tests con Jest
  - [ ] Configurar Jest
  - [ ] Crear setup
  - [ ] Tests unitarios chaining
  - [ ] Tests integración API
  - [ ] Ejecutar y validar coverage
```

---

**Tiempo estimado de implementación:** 2-3 sprints (20-30 días)  
**Prioridad recomendada:** Implementar en el orden presentado (DLQ → Idempotency → Health → Rate Limit → Tests)
