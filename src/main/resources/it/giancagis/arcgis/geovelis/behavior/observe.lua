-- One bounded hash per identity/service/layer. All updates and expiry are atomic.
-- TIME makes all ArcSOC instances use the same clock; windows use ingestion time.
local key = KEYS[1]
local clock = redis.call('TIME')
local now = tonumber(clock[1])
local event, query, batch = ARGV[1], ARGV[2], ARGV[3]
local offset, requested, effective, bytes, ids = tonumber(ARGV[4]), tonumber(ARGV[5]), tonumber(ARGV[6]), tonumber(ARGV[7]), tonumber(ARGV[8])
local broadIds, returned = ARGV[9] == '1', ARGV[10] == '1'
local frequencyLimit, pagingLimit, recordLimit, byteLimit = tonumber(ARGV[11]), tonumber(ARGV[12]), tonumber(ARGV[13]), tonumber(ARGV[14])
local function number(field) return tonumber(redis.call('HGET', key, field)) or 0 end
local function pack(values) return table.concat(values, ',') end
local function unpackNumbers(value)
    local result = {}
    for item in string.gmatch(value or '', '[^,]+') do result[#result+1] = tonumber(item) end
    return result
end
local function sum(a,b) return math.min(9007199254740991, a+b) end
if redis.call('HEXISTS', key, 'd:' .. event) == 1 then return {'DUPLICATE'} end
-- Rotate even continuously active sessions after eight hours.
local started = number('started')
if started > 0 and now - started >= 28800 then redis.call('DEL', key) end
if redis.call('HEXISTS', key, 'session') == 0 then
    redis.call('HSET', key, 'session', event, 'started', now)
end
-- Bounded deduplication ring: the last 256 accepted event IDs.
local n = number('events') + 1
local slot = 'ds:' .. (n % 256)
local old = redis.call('HGET', key, slot)
if old then redis.call('HDEL', key, 'd:' .. old) end
redis.call('HSET', key, slot, event, 'd:' .. event, 1, 'events', n)
local transitions = 0
local reordered = 0
if returned and offset >= 0 and query ~= '' then
    local field = 'q:' .. query
    local previous = unpackNumbers(redis.call('HGET', key, field))
    if #previous == 0 then
        local qn = number('queries') + 1
        local qs = 'qs:' .. (qn % 32)
        local oldQuery = redis.call('HGET', key, qs)
        if oldQuery then redis.call('HDEL', key, 'q:' .. oldQuery) end
        redis.call('HSET', key, qs, query, 'queries', qn)
    end
    if #previous == 4 and now - previous[3] < 60 and offset > 0 then
        if offset > previous[1] then
            if previous[2] > 0 and offset == previous[1] + previous[2] then transitions = previous[4] + 1 end
        else
            reordered = 1
        end
    end
    if reordered == 0 then redis.call('HSET', key, field, pack({offset,effective,now,transitions})) end
end
local newBatch = 0
if broadIds then redis.call('HSET', key, 'idsAt', now) end
local idsAt = number('idsAt')
if returned and ids > 0 and batch ~= '' and idsAt > 0 and now - idsAt < 300 then
    if redis.call('HEXISTS', key, 'i:' .. batch) == 0 then
        local bn = number('batches') + 1
        local bs = 'is:' .. (bn % 64)
        local oldBatch = redis.call('HGET', key, bs)
        if oldBatch then redis.call('HDEL', key, 'i:' .. oldBatch) end
        redis.call('HSET', key, bs, batch, 'i:' .. batch, 1, 'batches', bn)
        newBatch = 1
    end
end
-- Sixty one-second buckets, independent of the idle TTL.
local bucketField = 'b:' .. (now % 60)
local bucket = unpackNumbers(redis.call('HGET', key, bucketField))
if #bucket ~= 6 or bucket[1] ~= now then bucket = {now,0,0,0,0,0} end
bucket[2] = sum(bucket[2],1)
bucket[3] = sum(bucket[3],requested)
bucket[4] = sum(bucket[4],bytes)
bucket[5] = math.max(bucket[5],transitions)
bucket[6] = sum(bucket[6],newBatch)
redis.call('HSET', key, bucketField, pack(bucket))
local requests, records, responseBytes, sequence, idBatches = 0,0,0,0,0
for i=0,59 do
    local b = unpackNumbers(redis.call('HGET', key, 'b:' .. i))
    if #b == 6 and b[1] > now - 60 and b[1] <= now then
        requests = sum(requests,b[2])
        records = sum(records,b[3])
        responseBytes = sum(responseBytes,b[4])
        sequence = math.max(sequence,b[5])
        idBatches = sum(idBatches,b[6])
    end
end
local score = 0
local reasons = {}
local function signal(name, weight) reasons[#reasons+1] = name; score = score + weight end
if requests >= frequencyLimit then signal('HighQueryFrequency',25) end
if records >= recordLimit or responseBytes >= byteLimit then signal('HighVolume',25) end
if sequence >= pagingLimit then signal('SequentialPaging',35) end
if idBatches >= 3 then signal('IdsThenObjectIdBatches',40) end
score = math.min(100,score)
local peak = math.max(number('sessionPeak'),score)
redis.call('HSET', key, 'sessionPeak', peak, 'lastSeen', now,
    'sessionRequestedRecords',sum(number('sessionRequestedRecords'),requested),
    'sessionResponseBytes',sum(number('sessionResponseBytes'),bytes))
redis.call('EXPIRE', key, 300)
local decision = 'OBSERVE'
local threshold = tonumber(ARGV[15]) or 0
if returned and threshold > 0 and score >= threshold then
    if ARGV[16] == '1' then
        -- NX: later observations must not extend an existing block.
        if redis.call('SET', KEYS[2], '1', 'EX', 300, 'NX') then
            decision = 'BLOCK_CREATED'
        else decision = 'BLOCK_EXISTS' end
    else decision = 'WOULD_BLOCK' end
end
return {'OK', tostring(score), pack(reasons), tostring(requests), tostring(records), tostring(responseBytes),
    tostring(sequence), tostring(idBatches), redis.call('HGET',key,'session'), tostring(peak),
    tostring(n), tostring(reordered), tostring(now), decision}
