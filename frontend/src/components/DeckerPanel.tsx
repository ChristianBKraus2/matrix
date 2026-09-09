import { type DeckerStateDto } from '../types/messages'

interface Props {
  decker: DeckerStateDto
}

function DamageMonitor({
  label,
  damage,
  maxBoxes,
}: {
  label: string
  damage: number
  maxBoxes: number
}) {
  return (
    <div className="damage-monitor">
      <span className="monitor-label">{label}</span>
      <div className="monitor-boxes">
        {Array.from({ length: maxBoxes }, (_, i) => (
          <span key={i} className={`monitor-box ${i < damage ? 'damaged' : 'healthy'}`}>
            {i < damage ? '■' : '□'}
          </span>
        ))}
      </div>
      <span className="monitor-count">
        {damage}/{maxBoxes}
      </span>
    </div>
  )
}

export default function DeckerPanel({ decker }: Props) {
  return (
    <div className="panel decker-panel">
      <div className="panel-header">DECKER</div>
      <div className="panel-body">
        <div className="stat-value" style={{ fontSize: 24, letterSpacing: 2, marginBottom: 4 }}>
          {decker.name}
        </div>
        {decker.isPinnedByBlackIc && (
          <div className="pinned-alert">⚠ PINNED BY BLACK IC</div>
        )}

        <DamageMonitor label="PHYS" damage={decker.physicalDamage} maxBoxes={decker.physicalMaxBoxes} />
        <DamageMonitor label="MENT" damage={decker.mentalDamage} maxBoxes={decker.mentalMaxBoxes} />

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '2px 16px', marginBottom: 4 }}>
          {[
            { label: 'HACKING POOL', value: `${decker.hackingPool}d` },
            { label: 'HARDENING', value: String(decker.hardening) },
            { label: 'RESPONSE', value: String(decker.responseIncrease) },
          ].map(({ label, value }) => (
            <span key={label} style={{ whiteSpace: 'nowrap' }}>
              <span className="stat-label">{label}</span>{' '}
              <span className="stat-value">{value}</span>
            </span>
          ))}
        </div>
        <div className="stat-row">
          <span className="stat-label">R:B/E/M/S</span>
          <span className="stat-value">{decker.mcpRating}:{decker.bod}/{decker.evasion}/{decker.masking}/{decker.sensor}</span>
        </div>

        {decker.storedUtilities.length > 0 && (
          <>
            <div className="section-title">
              <span>PROGRAMS</span>
              <span className="programs-memory">
                {decker.freeActiveMemoryMp}/{decker.totalActiveMemoryMp} Mp | IO: {decker.ioSpeedMpPerTurn} Mp/t
                {decker.offlineStorageCount > 0 && ` | offline: ${decker.offlineStorageCount}`}
              </span>
            </div>
            <div>
              {decker.storedUtilities.map((u) => {
                const loaded = decker.activeUtilities.some((a) => a.type === u.type)
                return (
                  <div key={u.type} className={`program-row${loaded ? '' : ' program-unloaded'}`}>
                    <span className="program-name">{u.type}</span>
                    <span className="program-rating">
                      {'●'.repeat(Math.min(Math.max(0, u.rating), 10))}
                      {'○'.repeat(Math.max(0, 10 - Math.max(0, u.rating)))} ({u.rating})
                    </span>
                  </div>
                )
              })}
            </div>
          </>
        )}
      </div>
    </div>
  )
}
