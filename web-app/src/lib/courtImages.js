// Stable by persisted global court_code; courtNumber is local to its branch.
// These are illustrative images; never infer a court's real material or facilities from a photo.
const images = Object.freeze({
  "1": "/assets/courts/court-00001.webp",
  "2": "/assets/checkout-court-star-city.png",
  "3": "/assets/courts/court-00003.webp",
  "4": "/assets/courts/court-00004.webp",
  "5": "/assets/guide-court.jpg",
  "6": "/assets/courts/court-00006.webp",
  "7": "/assets/courts/court-00007.webp",
  "8": "/assets/courts/court-00008.webp",
  "10001": "/assets/courts/court-10001.webp",
  "10002": "/assets/courts/court-10002.webp",
  "10003": "/assets/courts/court-10003.webp",
  "10004": "/assets/courts/court-10004.webp",
  "10005": "/assets/courts/court-10005.webp",
  "10006": "/assets/courts/court-10006.webp",
  "10007": "/assets/courts/court-10007.webp",
  "20102": "/assets/courts/court-20102.webp",
  "20103": "/assets/courts/court-20103.webp",
  "20202": "/assets/courts/court-20202.webp",
  "20203": "/assets/courts/court-20203.webp",
  "20204": "/assets/courts/court-20204.webp",
  "20302": "/assets/courts/court-20302.webp",
  "20303": "/assets/courts/court-20303.webp",
  "20304": "/assets/courts/court-20304.webp",
  "20402": "/assets/courts/court-20402.webp",
  "20403": "/assets/courts/court-20403.webp",
  "20404": "/assets/courts/court-20404.webp",
  "20502": "/assets/courts/court-20502.webp",
  "20503": "/assets/courts/court-20503.webp",
  "20504": "/assets/courts/court-20504.webp",
  "20602": "/assets/courts/court-20602.webp",
  "20603": "/assets/courts/court-20603.webp",
  "20604": "/assets/courts/court-20604.webp",
  "20702": "/assets/courts/court-20702.webp",
  "20703": "/assets/courts/court-20703.webp",
  "20704": "/assets/courts/court-20704.webp"
});

export const COURT_FALLBACK_IMAGE = '/assets/guide-court.jpg';

export function courtImageSource(court, catalog = []) {
  const direct = court?.imageUrl?.startsWith('/assets/') ? court.imageUrl : images[court?.courtCode ?? court?.courtNumber];
  if (direct) return direct;
  // Resolve the branch's persisted representative, never array position or encoded UUID/number digits.
  const representative = court?.branchId && Array.isArray(catalog)
    ? catalog.find(item => item.branchId === court.branchId && item.branchCourtNumber === 1) : null;
  const branchImage = representative?.imageUrl?.startsWith('/assets/')
    ? representative.imageUrl : images[representative?.courtCode ?? representative?.courtNumber];
  return branchImage || COURT_FALLBACK_IMAGE;
}
