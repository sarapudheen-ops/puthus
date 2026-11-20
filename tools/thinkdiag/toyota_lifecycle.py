"""Exact fast-KWP lifecycle/frame joins and literal consumer-call projection.

This is not a diagnostic link or operation grant. The startup projection covers
only the first, successful fast-entry exchange. SYSP retries, transport-5 ISO
fallback, malformed replies, MIC3313 mapping and production emission are separate.
Keepalive is NOT universally 3E: the exact function binding may be a read request.
"""
from dataclasses import dataclass

from toyota_frames import SourceFrame, SourceFrames
from toyota_transport import ConsumerCall, FastKwpEntry, selected_fast_kwp

SYSP_SHA256 = '6c65bd75f924f17445bf9853b327a318c04debc88a17d14735aaa9c108b1c432'
CONSUMER_RECORDS = (
    'libTOYOTA_SYSP.so@0000fe0c#system-entry-dispatch',
    'libTOYOTA_SYSP.so@000109b8#fast-entry',
    'libTOYOTA_SYSP.so@00010210#keepalive-binding',
    'libTOYOTA_COMM.so@000187bc#keepalive-configuration',
    'libTOYOTA_SYSP.so@0001010c#system-exit',
    'libTOYOTA_COMM.so@00018460#exit-command',
)
REGIONS = {1: 'EU', 2: 'NA', 3: 'JP'}


@dataclass(frozen=True)
class FastKwpLifecycle:
    region: int
    function: int
    entry: FastKwpEntry
    initial: SourceFrame
    keepalive: SourceFrame
    exit: SourceFrame

    def __post_init__(self):
        if type(self.region) is not int or self.region not in REGIONS:
            raise ValueError('Exact lifecycle region required')
        if type(self.function) is not int or not 0 <= self.function <= 0xffffffff:
            raise ValueError('Exact lifecycle function required')
        if not isinstance(self.entry, FastKwpEntry):
            raise ValueError('Exact fast-KWP entry required')
        # Native SystemSetLinkKeepFunc uses just the first template word. Every
        # pinned transport-1/5 entry has exactly one word in each list. Do not
        # silently discard additional lifecycle commands in a different schema.
        if any(len(t) != 1 for t in (self.entry.initial_templates,
                                    self.entry.keepalive_templates, self.entry.exit_templates)):
            raise ValueError('Not the single-template fast-KWP lifecycle class')
        for name, frame, limit in (('initial', self.initial, 50), ('keepalive', self.keepalive, 20), ('exit', self.exit, 50)):
            if (not isinstance(frame, SourceFrame) or type(frame.frame_id) is not int
                    or not 0 <= frame.frame_id <= 65535
                    or any(not isinstance(v, bytes) for v in (frame.request, frame.mask, frame.response))
                    or not isinstance(frame.source_records, tuple) or not frame.source_records
                    or any(not isinstance(v, str) or not v.startswith(REGIONS[self.region] + '/') for v in frame.source_records)):
                raise ValueError(f'Invalid or cross-region {name} source frame')
            if len(self.entry.packet(frame.request)) > limit:
                raise ValueError(f'{name} packet exceeds native consumer capacity')
        # The fast consumer uses the source bytes as pulse arguments. No timing
        # defaults are inserted, and source flag 33 does not imply an ISO header.
        if not all(self.entry.timing[3:]):
            raise ValueError('Zero source fast-entry pulse argument')

    def fast_success_calls(self, *, connector, pin_pair_index):
        """Literal first-success trace, not the retry/negative-reply algorithm."""
        return self.entry.configuration_calls(connector=connector, pin_pair_index=pin_pair_index) + (
            ConsumerCall('BeginCombination', ()),
            ConsumerCall('SetCommunicationLineVoltage', (2, *self.entry.timing[3:])),
            ConsumerCall('CommSubFunc_00000003', (255, 255, 1, self.entry.packet(self.initial.request).hex())),
            ConsumerCall('EndCombination', ()),
        )

    def keepalive_calls(self, *, enabled):
        if type(enabled) is not bool:
            raise ValueError('Explicit keepalive enabled/disabled decision required')
        # COMM @18948..18968: the literal native SetLinkKeep arguments are
        # (wire, length, 3, 800) for BOTH native transport 1 and 5. The 800 is
        # not derived from app P3 defaults; the API's adapter semantics still
        # need mapping before use by a MIC3313 link.
        if not enabled:
            return (ConsumerCall('SetLinkKeep', ('', 0, 0, 0)),)
        wire = self.entry.packet(self.keepalive.request)
        return (ConsumerCall('SetLinkKeep', (wire.hex(), len(wire), 3, 800)),)

    def exit_calls(self):
        # Despite CmdSendWithoutRec's name, native COMM invokes this answer API
        # then usleep(500). SYSP does not enforce the bound frame's reply bytes.
        # Preserve them in report(); do not turn them into an invented handshake.
        wire = self.entry.packet(self.exit.request)
        return (
            ConsumerCall('KwpSendDataToEcuGetAnswer', (255, 255, 1, wire.hex())),
            ConsumerCall('usleep', (500,)),
        )

    def report(self):
        def frame(template, value):
            return {'template': template, 'frameId': value.frame_id,
                    'requestHex': value.request.hex(), 'maskHex': value.mask.hex(),
                    'responseHex': value.response.hex(), 'sourceRecords': list(value.source_records)}
        return {'kind': 'source-fast-kwp-lifecycle', 'region': self.region, 'function': self.function,
                'entry': self.entry.report(),
                'initial': frame(self.entry.initial_templates[0], self.initial),
                'keepalive': frame(self.entry.keepalive_templates[0], self.keepalive),
                'exit': frame(self.entry.exit_templates[0], self.exit),
                'requiresIsoFallbackPort': self.entry.transport == 5,
                'consumerLibrarySha256': SYSP_SHA256,
                'sourceRecords': list(dict.fromkeys((self.entry.source_record,
                    *self.initial.source_records, *self.keepalive.source_records, *self.exit.source_records, *CONSUMER_RECORDS)))}


def selected_fast_kwp_lifecycle(index, frames, *, region, function, selector):
    entry = selected_fast_kwp(index, region=region, function=function, selector=selector)
    if not isinstance(frames, SourceFrames) or frames.prefix != REGIONS[region] + '/':
        raise ValueError('Lifecycle needs the exact selected regional frame tables')
    def one(template):
        # The actual COMM consumer narrows w1 to its lower 16 bits. This is a
        # template-table key conversion, NOT fallback in the full u32 entry join.
        matches = frames.function(function & 0xffff, template)
        if len(matches) != 1:
            raise ValueError('No unique exact default lifecycle-template binding')
        return matches[0]
    if any(len(t) != 1 for t in (entry.initial_templates, entry.keepalive_templates, entry.exit_templates)):
        raise ValueError('Not the single-template fast-KWP lifecycle class')
    return FastKwpLifecycle(region, function, entry, *(one(t[0]) for t in
        (entry.initial_templates, entry.keepalive_templates, entry.exit_templates)))
